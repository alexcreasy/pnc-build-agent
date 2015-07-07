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

import io.termd.core.Status;
import io.termd.core.http.Bootstrap;
import io.termd.core.http.Task;
import io.termd.core.http.TaskCreationListener;
import io.termd.core.http.TaskStatusUpdateEvent;
import io.termd.core.http.TaskStatusUpdateListener;
import io.termd.core.tty.TtyConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * @author <a href="mailto:matejonnet@gmail.com">Matej Lazar</a>
 */
public class Main {

    Logger log = LoggerFactory.getLogger(Main.class);

    Bootstrap bootstrap;

    private final List<Task> runningTasks = new ArrayList<>();

    private final Set<TaskStatusUpdateListener> statusUpdateListeners = new HashSet<>();
    private File logFolder = Paths.get("").toFile();
    private Charset charset = Charset.forName("UTF-8");


    public boolean addStatusUpdateListener(TaskStatusUpdateListener statusUpdateListener) {
        return statusUpdateListeners.add(statusUpdateListener);
    }

    public boolean removeStatusUpdateListener(TaskStatusUpdateListener statusUpdateListener) {
        return statusUpdateListeners.remove(statusUpdateListener);
    }

    public static void main(String[] args) throws Exception {
        new Main().start("localhost", 8080, null);
    }


    public void start(String host, int port, final Runnable onStart) throws InterruptedException {
        bootstrap = new Bootstrap(taskCreationListener());

        UndertowBootstrap undertowBootstrap = new UndertowBootstrap(host, port, this, runningTasks);

        undertowBootstrap.bootstrap(new Consumer<Boolean>() {
            @Override
            public void accept(Boolean event) {
                if (event) {
                    System.out.println("Server started on " + 8080);
                    if (onStart != null) onStart.run();
                } else {
                    System.out.println("Could not start");
                }
            }
        });
    }

    private TaskCreationListener taskCreationListener() {
        return (task) -> {
            try {
                FileOutputStream fileOutputStream = registerProcessLogger(task);
                task.setTaskStatusUpdateListener(taskStatusUpdateListener(fileOutputStream));
                runningTasks.add(task);
            } catch (IOException e) {
                log.error("Cannot open fileChannel: ", e);
            }
        };
    }

    private TaskStatusUpdateListener taskStatusUpdateListener(FileOutputStream fileOutputStream) {
        return (taskStatusUpdateEvent) -> {
            Task task = taskStatusUpdateEvent.getTask();
            Status newStatus = taskStatusUpdateEvent.getNewStatus();
            switch (newStatus) {
                case SUCCESSFULLY_COMPLETED:
                case FAILED:
                case INTERRUPTED:
                    runningTasks.remove(task);
                    try {
                        String completed = "% # Finished with status: " + newStatus + "\r\n";
                        fileOutputStream.write(completed.getBytes(charset));
                        fileOutputStream.close();
                    } catch (IOException e) {
                        log.error("Cannot close log file channel: ", e);
                    }
            }
            ;
            notifyStatusUpdated(taskStatusUpdateEvent);
        };
    }

    private FileOutputStream registerProcessLogger(Task task) throws IOException {
        File logFile = new File(logFolder, "console-" + task.getId() + ".log");

        log.info("Opening log file ...");
        FileOutputStream fileOutputStream = new FileOutputStream(logFile, true);

        Consumer<String> processInputConsumer = (line) -> {

            try {
                String command = "% " + line + "\r\n";
                fileOutputStream.write(command.getBytes(charset));
            } catch (IOException e) {
                log.error("Cannot write task {} output to fileChannel", task.getId());
            }
        };

        Consumer<int[]> processOutputConsumer = (ints) -> {
            DataOutputStream out = new DataOutputStream(fileOutputStream);
            for (int anInt : ints) {
                try {
                    out.write(anInt);
                } catch (IOException e) {
                    log.error("Cannot write task {} output to fileChannel", task.getId());
                }
            }
        };

        task.setProcessInputConsumer(processInputConsumer);
        task.setProcessOutputConsumer(processOutputConsumer);
        return fileOutputStream;
    }

    void notifyStatusUpdated(TaskStatusUpdateEvent statusUpdateEvent) {
        for (TaskStatusUpdateListener statusUpdateListener : statusUpdateListeners) {
            log.debug("Notifying listener {} status update {}", statusUpdateListener, statusUpdateEvent);
            statusUpdateListener.accept(statusUpdateEvent);
        }
    }

    public Consumer<TtyConnection> getBootstrap() {
        return bootstrap;
    }
}
