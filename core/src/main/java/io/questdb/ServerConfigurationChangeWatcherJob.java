/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2023 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb;

import io.questdb.cairo.O3OpenColumnJob;
import io.questdb.cutlass.json.JsonException;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.Filewatcher;
import io.questdb.std.str.Path;
import io.questdb.mp.SynchronizedJob;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.Properties;

public class ServerConfigurationChangeWatcherJob extends SynchronizedJob implements Closeable {
    private final long watcherAddress;
    private Bootstrap bootstrap;
    private ServerMain serverMain;
    private final static Log LOG = LogFactory.getLog(ServerConfigurationChangeWatcherJob.class);
    private final boolean addShutdownhook;
    private Properties properties;

    public ServerConfigurationChangeWatcherJob(Bootstrap bootstrap, ServerMain serverMain, boolean addShutdownHook) throws IOException {
        this.bootstrap = bootstrap;
        this.properties = bootstrap.loadProperties();
        this.serverMain = serverMain;
        this.addShutdownhook = addShutdownHook;

        final String configFilePath = this.bootstrap.getConfiguration().getCairoConfiguration().getConfRoot() + Bootstrap.CONFIG_FILE;

        try (Path path = new Path()) {
            path.of(configFilePath).$();
            this.watcherAddress = Filewatcher.setup(path.ptr());
        }
    }

    public void close() {
        Filewatcher.teardown(this.watcherAddress);
    }

    @Override
    protected boolean runSerially() {
        if (serverMain == null) {
            return true;
        }

        if (Filewatcher.changed(this.watcherAddress)) {
            Properties newProperties;
            try {
                newProperties = this.bootstrap.loadProperties();
            } catch (IOException exc) {
                LOG.error().$("error loading properties").$();
                return false;
            }

            if (!newProperties.equals(this.properties)) {
                LOG.info().$("config successfully reloaded").$();
                serverMain.close();
                this.bootstrap = new Bootstrap(this.bootstrap.args);
                serverMain = new ServerMain(this.bootstrap);
                serverMain.start(this.addShutdownhook);
                this.properties = newProperties;
            }
        }
        return true;
    }

}