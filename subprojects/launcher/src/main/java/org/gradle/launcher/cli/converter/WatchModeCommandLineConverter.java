/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.launcher.cli.converter;

import org.gradle.api.JavaVersion;
import org.gradle.cli.AbstractCommandLineConverter;
import org.gradle.cli.CommandLineArgumentException;
import org.gradle.cli.CommandLineParser;
import org.gradle.cli.ParsedCommandLine;
import org.gradle.launcher.watch.WatchModeParameters;

public class WatchModeCommandLineConverter extends AbstractCommandLineConverter<WatchModeParameters> {

    private static final String WATCH = "watchmode";

    public WatchModeParameters convert(ParsedCommandLine args, WatchModeParameters target) throws CommandLineArgumentException {
        if (args.hasOption(WATCH)) {
            assertJava7OrBetter();
            return target.setEnabled(true);
        }
        return target;
    }

    private void assertJava7OrBetter() {
        if (!JavaVersion.current().isJava7Compatible()) {
            throw new CommandLineArgumentException(String.format("Continuous mode (--%s) is not supported on versions of Java older than %s.", WATCH, JavaVersion.VERSION_1_7));
        }
    }

    public void configure(CommandLineParser parser) {
        parser.option(WATCH).hasDescription("Enables 'continuous mode'. Gradle does not exit and will re-execute tasks based on a trigger.").incubating();
    }
}
