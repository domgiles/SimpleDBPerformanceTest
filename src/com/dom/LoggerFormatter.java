package com.dom;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.util.Date;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

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

public class LoggerFormatter extends Formatter {
    private static final DateFormat df = DateFormat.getTimeInstance(DateFormat.MEDIUM);

    public LoggerFormatter() {
        super();
    }

    public String format(LogRecord record) {

        StringBuffer sb = new StringBuffer();
        sb.append(String.format("%s %-8s [%s] %-8s %s() %s", df.format(new Date(record.getMillis())),  record.getLevel(), record.getThreadID(), record.getLoggerName(), record.getSourceMethodName(), formatMessage(record)));
        if (record.getThrown() != null) {
            sb.append(" ").append(record.getThrown().getMessage());
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            record.getThrown().printStackTrace(pw);
            sb.append("\n");
            sb.append(sw.toString());
        }

        sb.append("\n");

        return sb.toString();
    }
}
