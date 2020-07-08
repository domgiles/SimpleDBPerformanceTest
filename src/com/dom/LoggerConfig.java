package com.dom;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Properties;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/*
 * Copyright 2020 Dominic Giles. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

public class LoggerConfig {
    private static final Logger mainlogger = Logger.getLogger("com.dom");
    private static final Logger logger = Logger.getLogger(com.dom.LoggerConfig.class.getName());

    public LoggerConfig() {
        try {

            Properties props = new Properties();
            props.setProperty("handlers","java.util.logging.ConsoleHandler,java.util.logging.FileHandler");
            props.setProperty("java.util.logging.ConsoleHandler.level", "FINE");
            props.setProperty("java.util.logging.ConsoleHandler.formatter","com.dom.LoggerFormatter");
            props.setProperty("java.util.logging.FileHandler.formatter","com.dom.LoggerFormatter");
            props.setProperty("java.util.logging.FileHandler.append","false");
            props.setProperty(".level","INFO");
            props.setProperty("com.dom.level","FINE");
            PipedInputStream in = new PipedInputStream();
            PipedOutputStream out = new PipedOutputStream(in);
            props.store(out, "");
            out.close();
            LogManager.getLogManager().readConfiguration(in);
            FileHandler fh = new FileHandler("debug.log", true);
            mainlogger.addHandler(fh);
            in.close();
            logger.finest("Finished setting Logging properties");

        } catch (SecurityException se) {
            logger.log(Level.SEVERE, "Unable to set up logging profile", se);
        } catch (IOException ioe) {
            logger.log(Level.SEVERE, "Unable to set up logging profile", ioe);
            ioe.printStackTrace();
        }
    }
}