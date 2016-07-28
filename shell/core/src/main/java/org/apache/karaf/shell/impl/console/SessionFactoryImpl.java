/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.karaf.shell.impl.console;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

import org.apache.felix.gogo.jline.Builtin;
import org.apache.felix.gogo.runtime.CommandProcessorImpl;
import org.apache.felix.service.command.CommandSession;
import org.apache.felix.service.command.Function;
import org.apache.felix.service.threadio.ThreadIO;
import org.apache.karaf.shell.api.console.Command;
import org.apache.karaf.shell.api.console.Completer;
import org.apache.karaf.shell.api.console.Parser;
import org.apache.karaf.shell.api.console.Registry;
import org.apache.karaf.shell.api.console.Session;
import org.apache.karaf.shell.api.console.SessionFactory;
import org.apache.karaf.shell.api.console.Terminal;
import org.apache.karaf.shell.impl.console.commands.ExitCommand;
import org.apache.karaf.shell.impl.console.commands.SubShellCommand;
import org.apache.karaf.shell.impl.console.commands.help.HelpCommand;

public class SessionFactoryImpl extends RegistryImpl implements SessionFactory, Registry {

    final CommandProcessorImpl commandProcessor;
    final ThreadIO threadIO;
    final Map<String, SubShellCommand> subshells = new HashMap<String, SubShellCommand>();
    boolean closed;

    public SessionFactoryImpl(ThreadIO threadIO) {
        super(null);
        this.threadIO = threadIO;
        commandProcessor = new CommandProcessorImpl(threadIO);
        register(new ExitCommand());
        new HelpCommand(this);
        register(new JobCommand("jobs", "List shell jobs", (session, args) -> new Builtin().jobs(session, args)));
        register(new JobCommand("fg", "Put job in foreground", (session, args) -> new Builtin().fg(session, args)));
        register(new JobCommand("bg", "Put job in background", (session, args) -> new Builtin().bg(session, args)));
    }

    public CommandProcessorImpl getCommandProcessor() {
        return commandProcessor;
    }

    @Override
    public Registry getRegistry() {
        return this;
    }

    @Override
    public void register(Object service) {
        synchronized (services) {
            if (service instanceof Command) {
                Command command = (Command) service;
                String scope = command.getScope();
                String name = command.getName();
                if (!Session.SCOPE_GLOBAL.equals(scope)) {
                    if (!subshells.containsKey(scope)) {
                        SubShellCommand subShell = new SubShellCommand(scope);
                        subshells.put(scope, subShell);
                        register(subShell);
                    }
                    subshells.get(scope).increment();
                }
                commandProcessor.addCommand(scope, wrap(command), name);
            }
            super.register(service);
        }
    }

    protected Function wrap(Command command) {
        return new CommandWrapper(command);
    }

    @Override
    public void unregister(Object service) {
        synchronized (services) {
            super.unregister(service);
            if (service instanceof Command) {
                Command command = (Command) service;
                String scope = command.getScope();
                String name = command.getName();
                commandProcessor.removeCommand(scope, name);
                if (!Session.SCOPE_GLOBAL.equals(scope)) {
                    if (subshells.get(scope).decrement() == 0) {
                        SubShellCommand subShell = subshells.remove(scope);
                        unregister(subShell);
                    }
                }
            }
        }
    }

    @Override
    public Session create(InputStream in, PrintStream out, PrintStream err, Terminal term, String encoding, Runnable closeCallback) {
        synchronized (commandProcessor) {
            if (closed) {
                throw new IllegalStateException("SessionFactory has been closed");
            }
            final Session session = new ConsoleSessionImpl(this, commandProcessor, threadIO, in, out, err, term, encoding, closeCallback);
            return session;
        }
    }

    @Override
    public Session create(InputStream in, PrintStream out, PrintStream err) {
        return create(in, out, err, null);
    }

    @Override
    public Session create(InputStream in, PrintStream out, PrintStream err, Session parent) {
        synchronized (commandProcessor) {
            if (closed) {
                throw new IllegalStateException("SessionFactory has been closed");
            }
            final Session session = new HeadlessSessionImpl(this, commandProcessor, in, out, err, parent);
            return session;
        }
    }

    public void stop() {
        synchronized (commandProcessor) {
            closed = true;
            commandProcessor.stop();
        }
    }

    private class JobCommand implements Command {
        private final String name;
        private final String desc;
        private final BiConsumer<CommandSession, String[]> consumer;

        public JobCommand(String name, String desc, BiConsumer<CommandSession, String[]> consumer) {
            this.name = name;
            this.desc = desc;
            this.consumer = consumer;
        }

        @Override
        public String getScope() {
            return "shell";
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getDescription() {
            return desc;
        }

        @Override
        public Completer getCompleter(boolean scoped) {
            return null;
        }

        @Override
        public Parser getParser() {
            return null;
        }

        @Override
        public Object execute(Session session, List<Object> arguments) throws Exception {
            CommandSession cmdSession = (CommandSession) session.get(".commandSession");
            String[] args = new String[arguments.size()];
            for (int i = 0; i < args.length; i++) {
                args[i] = Objects.toString(arguments.get(i));
            }
            consumer.accept(cmdSession, args);
            return null;
        }
    }

}
