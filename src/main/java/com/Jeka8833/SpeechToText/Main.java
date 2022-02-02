package com.Jeka8833.SpeechToText;

import com.google.gson.Gson;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import picocli.CommandLine;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "VoskSpeechToText", mixinStandardHelpOptions = true, version = "VoskSpeechToText 1.0.1",
        description = "Converts video/audio to text")
public class Main implements Callable<Integer> {

    private static final Gson GSON = new Gson();

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @CommandLine.Option(names = {"-m", "--model"}, required = true, description = "Folder to trained vosk model")
    private Path modelPath;

    @CommandLine.Option(names = {"-f", "--ffmpeg"}, required = true, description = ".EXE file ffmpeg")
    private Path ffmpeg;

    @CommandLine.Option(names = {"-i", "--input"}, required = true, description = "Input video/audio file")
    private Path input;

    @CommandLine.Option(names = {"-o", "--output"}, required = true, description = "Folder to output files")
    private Path output;

    public static void main(String[] args) throws IOException, UnsupportedAudioFileException {
        LibVosk.setLogLevel(LogLevel.DEBUG);

        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        if (!Files.isDirectory(modelPath))
            throw new CommandLine.ParameterException(spec.commandLine(), "Folder Vosk model not found");
        if (!Files.isRegularFile(ffmpeg))
            throw new CommandLine.ParameterException(spec.commandLine(), "It is not ffmpeg.exe file");
        if (!Files.isRegularFile(input))
            throw new CommandLine.ParameterException(spec.commandLine(), "Input file not found");
        Files.createDirectories(output);
        final String inputFileName = input.getFileName().toString();
        final String name = inputFileName.substring(0, inputFileName.lastIndexOf("."));

        final Path outTempPath = output.toAbsolutePath().resolve(name + "-temp.wav");
        final Process process = Runtime.getRuntime().exec('"' + ffmpeg.toAbsolutePath().toString() + "\" -y -i \""
                + input.toAbsolutePath() + "\" -ac 1 -ar 16000 " + outTempPath);
        logOutput(process.getInputStream());
        logOutput(process.getErrorStream());
        process.waitFor();

        final long fileSize = Files.size(outTempPath);
        long currentSize = 0;

        final StringBuilder outText = new StringBuilder();
        final StringBuilder outTimeLineText = new StringBuilder();

        try (Model model = new Model(modelPath.toAbsolutePath().toString());
             InputStream ais = AudioSystem.getAudioInputStream(new BufferedInputStream(Files.newInputStream(outTempPath)));
             Recognizer recognizer = new Recognizer(model, 16000)) {
            recognizer.setWords(true);

            int nbytes;
            byte[] b = new byte[4096 * 4];
            while ((nbytes = ais.read(b)) >= 0) {
                currentSize += nbytes;
                if (recognizer.acceptWaveForm(b, nbytes)) {
                    final String res = recognizer.getResult();
                    final WordConstructor constructor = GSON.fromJson(res, WordConstructor.class);
                    outText.append(constructor.text).append(" ");
                    if (!constructor.result.isEmpty())
                        outTimeLineText.append(constructor.result.get(0).start).append(": ").append(constructor.text).append("\n");
                    System.out.println("Process state: " + currentSize + "/" + fileSize + " (" + (((double) currentSize / fileSize) * 100) + "%)");
                }
            }
        }

        Files.deleteIfExists(outTempPath);
        Files.writeString(output.toAbsolutePath().resolve(name + ".txt"),
                new String(outText.toString().getBytes("windows-1251"), StandardCharsets.UTF_8));
        Files.writeString(output.toAbsolutePath().resolve(name + "-time.txt"),
                new String(outTimeLineText.toString().getBytes("windows-1251"), StandardCharsets.UTF_8));
        return 0;
    }

    private void logOutput(InputStream inputStream) {
        new Thread(() -> {
            Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8);
            while (scanner.hasNextLine()) {
                synchronized (this) {
                    System.out.println(scanner.nextLine());
                }
            }
            scanner.close();
        }).start();
    }

    private static class WordConstructor {
        private String text;
        private List<ResultList> result = new ArrayList<>();
    }

    private static class ResultList {
        private double start;
    }

}
