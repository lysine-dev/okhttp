/*
 * Copyright (C) 2020 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package okhttp3.okio

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Sink
import okio.Source

class LoggingFilesystem(
  fileSystem: FileSystem,
) : ForwardingFileSystem(fileSystem) {
  fun log(line: String) {
    println(line)
  }

  override fun appendingSink(
    file: Path,
    mustExist: Boolean,
  ): Sink {
    log("appendingSink($file)")

    return super.appendingSink(file, mustExist)
  }

  override fun atomicMove(
    source: Path,
    target: Path,
  ) {
    log("atomicMove($source, $target)")

    super.atomicMove(source, target)
  }

  override fun createDirectory(
    dir: Path,
    mustCreate: Boolean,
  ) {
    log("createDirectory($dir)")

    super.createDirectory(dir, mustCreate)
  }

  override fun delete(
    path: Path,
    mustExist: Boolean,
  ) {
    log("delete($path)")

    super.delete(path, mustExist)
  }

  override fun sink(
    file: Path,
    mustCreate: Boolean,
  ): Sink {
    log("sink($file)")

    return super.sink(file, mustCreate)
  }

  override fun source(file: Path): Source {
    log("source($file)")

    return super.source(file)
  }
}
