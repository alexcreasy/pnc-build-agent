/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.pnc.buildagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * @author <a href="mailto:matejonnet@gmail.opecom">Matej Lazar</a>
 */
public class IoLogger implements Closeable {

    Logger log = LoggerFactory.getLogger(IoLogger.class);
    private Charset charset = Charset.defaultCharset();
    private Consumer<String> inputLogger;
    private Consumer<byte[]> outputLogger;

    FileOutputStream stream;

    public IoLogger(Path logFolder) {
        Optional<FileOutputStream> fileOutputStream = Optional.empty();

        try {
            Path logPath = logFolder.resolve("console.log");

            log.info("Opening log file {}.", logPath);
            stream = new FileOutputStream(logPath.toFile(), true); //TODO close stream

            inputLogger = (line) -> {
                try {
                    String command = "% " + line + "\r\n";
                    stream.write(command.getBytes(charset));
                } catch (IOException e) {
                    log.error("Cannot write command line to log file.", e);
                }
            };

            outputLogger = (bytes) -> {
                try {
                    stream.write(bytes);
                } catch (IOException e) {
                    log.error("Cannot write output to file.", e);
                }
            };

        } catch (IOException e) {
            log.error("Cannot open fileChannel: ", e);
        }
    }


    public void close() {
        try {
            stream.close();
        } catch (IOException e) {
            log.error("Cannot close log output stream.", e);
        }
    }

    public Consumer<String> getInputLogger() {
        return inputLogger;
    }

    public Consumer<byte[]> getOutputLogger() {
        return outputLogger;
    }

    public void write(String message) {
        try {
            stream.write(message.getBytes(charset));
        } catch (IOException e) {
            log.error("cannot write message: " + message + " to log.", e);
        }
    }
}
