package flabbergast;

import java.io.File;
import java.io.IOException;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

public class MainPrinter {
    public static void main(String[] args) {
        Options options = new Options();
        options.addOption("o", "output", true,
                          "Write output to file instead of standard output.");
        options.addOption("t", "trace-parsing", false,
                          "Produce a trace of the parse process.");
        options.addOption("p", "no-precomp", false,
                          "Do not use precompiled libraries");
        options.addOption("h", "help", false, "Show this message and exit");
        CommandLineParser cl_parser = new GnuParser();
        CommandLine result;

        try {
            result = cl_parser.parse(options, args);
        } catch (ParseException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }

        if (result.hasOption('h')) {
            HelpFormatter formatter = new HelpFormatter();
            System.err
            .println("Run a Flabbergast file and display the “value” attribute.");
            formatter.printHelp("gnu", options);
            System.exit(1);
        }

        String[] files = result.getArgs();
        if (files.length != 1) {
            System.err.println("Exactly one Flabbergast script must be given.");
            System.exit(1);
        }

        ResourcePathFinder resource_finder = new ResourcePathFinder();
        try {
            resource_finder.prependPath(new File(new File(files[0]).getParentFile(),
                                                 "lib").getCanonicalPath());
        } catch (IOException e) {
            e.printStackTrace();
        }
        resource_finder.addDefaults();
        ErrorCollector collector = new ConsoleCollector();
        DynamicCompiler compiler = new DynamicCompiler(collector);
        compiler.setFinder(resource_finder);
        ConsoleTaskMaster task_master = new ConsoleTaskMaster();
        task_master.addUriHandler(new CurrentInformation(false));
        task_master.addUriHandler(BuiltInLibraries.INSTANCE);
        JdbcUriHandler jdbc_handler = new JdbcUriHandler();
        jdbc_handler.setFinder(resource_finder);
        task_master.addUriHandler(jdbc_handler);
        task_master.addUriHandler(SettingsHandler.INSTANCE);
        task_master.addUriHandler(EnvironmentUriHandler.INSTANCE);
        task_master.addUriHandler(FtpHandler.INSTANCE);
        task_master.addUriHandler(HttpHandler.INSTANCE);
        task_master.addUriHandler(FileHandler.INSTANCE);
        ResourceHandler resource_handler = new ResourceHandler();
        resource_handler.setFinder(resource_finder);
        task_master.addUriHandler(resource_handler);
        if (!result.hasOption('p')) {
            LoadPrecompiledLibraries precomp = new LoadPrecompiledLibraries();
            task_master.addUriHandler(precomp);
            precomp.setFinder(resource_finder);
        }
        task_master.addUriHandler(compiler);
        try {
            Parser parser = Parser.open(files[0]);
            parser.setTrace(result.hasOption('t'));
            Class<? extends Computation> run_type = parser.parseFile(collector,
                                                    compiler.getCompilationUnit(), "Printer");
            if (run_type != null) {
                Computation computation = run_type.getConstructor(
                                              TaskMaster.class).newInstance(task_master);
                PrintResult filewriter = new PrintResult(task_master,
                        computation, result.getOptionValue('o'));
                filewriter.slot();
                task_master.run();
                task_master.reportCircularEvaluation();
                System.exit(filewriter.getSuccess() ? 0 : 1);
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
            e.printStackTrace();
        }
        System.exit(1);
    }
}
