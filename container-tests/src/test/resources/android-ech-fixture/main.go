package main

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/binary"
	"encoding/pem"
	"fmt"
	"io"
	"log"
	"math/big"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	greenName          = "green.secret.test"
	greenPublicName    = "green.public.test"
	retryName          = "retry.secret.test"
	retryPublicName    = "retry.public.test"
	disabledName       = "disabled.secret.test"
	disabledPublicName = "disabled.public.test"
	dohName            = "doh.test"
)

type echKey struct {
	config    []byte
	private   []byte
	retryable bool
}

func main() {
	if len(os.Args) != 2 {
		log.Fatal("usage: fixture target|doh")
	}

	switch os.Args[1] {
	case "target":
		runTarget()
	case "doh":
		runDoH()
	default:
		log.Fatalf("unknown mode %q", os.Args[1])
	}
}

func runTarget() {
	caCert, caKey, caPEM := newCA()
	targetCertPEM, targetKeyPEM := newLeaf(
		caCert,
		caKey,
		greenName,
		greenPublicName,
		retryName,
		retryPublicName,
		disabledName,
		disabledPublicName,
	)
	dohCertPEM, dohKeyPEM := newLeaf(caCert, caKey, dohName)
	targetCertificate, err := tls.X509KeyPair(targetCertPEM, targetKeyPEM)
	must(err)

	greenKey := newECHKey(1, greenPublicName, true)
	retryStaleKey := newECHKey(2, retryPublicName, false)
	retryKey := newECHKey(3, retryPublicName, true)
	disabledStaleKey := newECHKey(4, disabledPublicName, false)
	disabledKey := newECHKey(5, disabledPublicName, false)

	metadata := strings.Join([]string{
		"ECH_GREEN_CONFIG_LIST=" + base64.StdEncoding.EncodeToString(configList(greenKey)),
		"ECH_RETRY_STALE_CONFIG_LIST=" + base64.StdEncoding.EncodeToString(configList(retryStaleKey)),
		"ECH_DISABLED_STALE_CONFIG_LIST=" + base64.StdEncoding.EncodeToString(configList(disabledStaleKey)),
		"DOH_CERT=" + base64.StdEncoding.EncodeToString(dohCertPEM),
		"DOH_KEY=" + base64.StdEncoding.EncodeToString(dohKeyPEM),
		"CA_CERT=" + base64.StdEncoding.EncodeToString(caPEM),
	}, "\n") + "\n"

	go func() {
		mux := http.NewServeMux()
		mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "ok") })
		mux.HandleFunc("/metadata", func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, metadata) })
		log.Fatal(http.ListenAndServe(":8080", mux))
	}()

	tlsConfig := &tls.Config{
		Certificates: []tls.Certificate{targetCertificate},
		MinVersion:   tls.VersionTLS13,
		GetEncryptedClientHelloKeys: func(hello *tls.ClientHelloInfo) ([]tls.EncryptedClientHelloKey, error) {
			var key echKey
			switch hello.ServerName {
			case greenPublicName:
				key = greenKey
			case retryPublicName:
				key = retryKey
			case disabledPublicName:
				key = disabledKey
			default:
				return []tls.EncryptedClientHelloKey{}, nil
			}
			return []tls.EncryptedClientHelloKey{{
				Config:      key.config,
				PrivateKey:  key.private,
				SendAsRetry: key.retryable,
			}}, nil
		},
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprintf(w, `{"echAccepted":%t,"serverName":%q}`, r.TLS.ECHAccepted, r.TLS.ServerName)
	})
	server := &http.Server{Addr: ":8443", Handler: mux, TLSConfig: tlsConfig}
	log.Fatal(server.ListenAndServeTLS("", ""))
}

func runDoH() {
	certPEM := decodeEnvironment("DOH_CERT")
	keyPEM := decodeEnvironment("DOH_KEY")
	echConfigs := map[string][]byte{
		greenName:    decodeEnvironment("ECH_GREEN_CONFIG_LIST"),
		retryName:    decodeEnvironment("ECH_RETRY_STALE_CONFIG_LIST"),
		disabledName: decodeEnvironment("ECH_DISABLED_STALE_CONFIG_LIST"),
	}
	targetPort, err := strconv.Atoi(requiredEnvironment("TARGET_PORT"))
	must(err)
	certificate, err := tls.X509KeyPair(certPEM, keyPEM)
	must(err)

	mux := http.NewServeMux()
	mux.HandleFunc("/health", func(w http.ResponseWriter, _ *http.Request) { _, _ = io.WriteString(w, "ok") })
	mux.HandleFunc("/dns-query", func(w http.ResponseWriter, r *http.Request) {
		query, err := readDnsQuery(r)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		response, err := dnsResponse(query, echConfigs, targetPort)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write(response)
	})
	server := &http.Server{
		Addr:    ":8053",
		Handler: mux,
		TLSConfig: &tls.Config{
			Certificates: []tls.Certificate{certificate},
			MinVersion:   tls.VersionTLS13,
		},
	}
	log.Fatal(server.ListenAndServeTLS("", ""))
}

func readDnsQuery(r *http.Request) ([]byte, error) {
	if r.Method == http.MethodPost {
		return io.ReadAll(io.LimitReader(r.Body, 65536))
	}
	if r.Method == http.MethodGet {
		encoded := r.URL.Query().Get("dns")
		return base64.RawURLEncoding.DecodeString(encoded)
	}
	return nil, fmt.Errorf("unsupported method %s", r.Method)
}

func dnsResponse(query []byte, echConfigs map[string][]byte, targetPort int) ([]byte, error) {
	if len(query) < 17 {
		return nil, fmt.Errorf("short DNS query")
	}
	questionEnd, questionName, err := dnsQuestion(query)
	if err != nil {
		return nil, err
	}
	qtype := binary.BigEndian.Uint16(query[questionEnd-4 : questionEnd-2])
	var rdata []byte
	switch qtype {
	case 1: // A
		rdata = []byte{127, 0, 0, 1}
	case 65: // HTTPS
		echConfigList, ok := echConfigs[questionName]
		if !ok {
			return nil, fmt.Errorf("no ECH fixture for %q", questionName)
		}
		rdata = httpsRecord(echConfigList, targetPort)
	case 28: // AAAA: a successful response with no answers.
	default:
	}

	answerCount := uint16(0)
	if rdata != nil {
		answerCount = 1
	}
	response := make([]byte, 12)
	copy(response[0:2], query[0:2])
	binary.BigEndian.PutUint16(response[2:4], 0x8180)
	binary.BigEndian.PutUint16(response[4:6], 1)
	binary.BigEndian.PutUint16(response[6:8], answerCount)
	response = append(response, query[12:questionEnd]...)
	if rdata == nil {
		return response, nil
	}
	response = append(response, 0xc0, 0x0c)
	response = appendUint16(response, qtype)
	response = appendUint16(response, 1)
	response = appendUint32(response, 60)
	response = appendUint16Length(response, rdata)
	return response, nil
}

func dnsQuestion(query []byte) (int, string, error) {
	position := 12
	var labels []string
	for {
		if position >= len(query) {
			return 0, "", fmt.Errorf("invalid DNS name")
		}
		length := int(query[position])
		position++
		if length == 0 {
			break
		}
		if length > 63 || position+length > len(query) {
			return 0, "", fmt.Errorf("invalid DNS label")
		}
		labels = append(labels, string(query[position:position+length]))
		position += length
	}
	if position+4 > len(query) {
		return 0, "", fmt.Errorf("short DNS question")
	}
	return position + 4, strings.ToLower(strings.Join(labels, ".")), nil
}

func httpsRecord(echConfigList []byte, targetPort int) []byte {
	result := appendUint16(nil, 1) // SvcPriority.
	result = append(result, 0)     // TargetName is the owner name.
	result = appendSvcParam(result, 1, []byte{2, 'h', '2', 8, 'h', 't', 't', 'p', '/', '1', '.', '1'})
	port := make([]byte, 2)
	binary.BigEndian.PutUint16(port, uint16(targetPort))
	result = appendSvcParam(result, 3, port)
	result = appendSvcParam(result, 4, []byte{127, 0, 0, 1})
	result = appendSvcParam(result, 5, echConfigList)
	return result
}

func appendSvcParam(dst []byte, key uint16, value []byte) []byte {
	dst = appendUint16(dst, key)
	return appendUint16Length(dst, value)
}

func marshalECHConfig(id byte, publicKey []byte, publicName string, maxNameLength byte) []byte {
	contents := []byte{id}
	contents = appendUint16(contents, 0x0020) // DHKEM(X25519, HKDF-SHA256).
	contents = appendUint16Length(contents, publicKey)
	cipherSuites := appendUint16(nil, 0x0001)         // HKDF-SHA256.
	cipherSuites = appendUint16(cipherSuites, 0x0001) // AES-128-GCM.
	contents = appendUint16Length(contents, cipherSuites)
	contents = append(contents, maxNameLength, byte(len(publicName)))
	contents = append(contents, publicName...)
	contents = appendUint16(contents, 0) // Extensions.

	config := appendUint16(nil, 0xfe0d)
	return appendUint16Length(config, contents)
}

func newECHKey(id byte, publicName string, retryable bool) echKey {
	privateKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	must(err)
	return echKey{
		config:    marshalECHConfig(id, privateKey.PublicKey().Bytes(), publicName, 64),
		private:   privateKey.Bytes(),
		retryable: retryable,
	}
}

func configList(key echKey) []byte {
	return appendUint16Length(nil, key.config)
}

func appendUint16(dst []byte, value uint16) []byte {
	return binary.BigEndian.AppendUint16(dst, value)
}

func appendUint32(dst []byte, value uint32) []byte {
	return binary.BigEndian.AppendUint32(dst, value)
}

func appendUint16Length(dst, value []byte) []byte {
	dst = appendUint16(dst, uint16(len(value)))
	return append(dst, value...)
}

func newCA() (*x509.Certificate, *ecdsa.PrivateKey, []byte) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	must(err)
	now := time.Now()
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "OkHttp ECH Test CA"},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(24 * time.Hour),
		IsCA:                  true,
		BasicConstraintsValid: true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
	}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	must(err)
	certificate, err := x509.ParseCertificate(der)
	must(err)
	return certificate, key, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
}

func newLeaf(ca *x509.Certificate, caKey *ecdsa.PrivateKey, names ...string) ([]byte, []byte) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	must(err)
	now := time.Now()
	template := &x509.Certificate{
		SerialNumber: big.NewInt(now.UnixNano()),
		Subject:      pkix.Name{CommonName: names[0]},
		DNSNames:     names,
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, template, ca, &key.PublicKey, caKey)
	must(err)
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	must(err)
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})
}

func requiredEnvironment(name string) string {
	value := os.Getenv(name)
	if value == "" {
		log.Fatalf("%s is not set", name)
	}
	return value
}

func decodeEnvironment(name string) []byte {
	value, err := base64.StdEncoding.DecodeString(requiredEnvironment(name))
	must(err)
	return value
}

func must(err error) {
	if err != nil {
		log.Fatal(err)
	}
}
