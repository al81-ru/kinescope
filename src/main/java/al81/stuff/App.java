package al81.stuff;

import al81.stuff.misc.ReqHeaderConfigReader;
import al81.stuff.parsers.HtmlPlayerParser;
import al81.stuff.parsers.MpdParser;
import al81.stuff.providers.SavedPageProvider;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

public class App {
    private static final String APP_CONFIG_PATH_PARAMETER = "configDir";
    private static final String APP_PROPERTIES_FILENAME = "app.properties";

    private static final String HEADER_CONTENT_ENCODING = "Content-Encoding";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final Properties appProperties;

    private final ReqHeaderConfigReader reqHeaderConfig;



    public static void main(String[] args) throws Exception {
        Properties properties = getAppProperties();
        ReqHeaderConfigReader reqHeaderConfigReader;

        String reqHeadersFileName = properties.getProperty("request.headers");

        try {
            Path reqHeadersPath = Path.of(reqHeadersFileName);
            reqHeaderConfigReader = ReqHeaderConfigReader.loadReader(reqHeadersPath);
        } catch (IOException e) {
            String value = System.getProperty(APP_CONFIG_PATH_PARAMETER);
            Path reqHeadersPath = Path.of(value, reqHeadersFileName);
            reqHeaderConfigReader = ReqHeaderConfigReader.loadReader(reqHeadersPath);
        }

        App app = new App(properties, reqHeaderConfigReader);


        //TODO command line params

        // direct url processing
        /*
        if (args.length == 0) {
            System.out.println("Specify embed player url");
            System.exit(1);
        }
        String prefix = args[0];
        for (int i = 1; i < args.length; i++) {
            app.process(prefix, args[i]);
        }
        */

        // dir with saved pages
        for (File file : new File("c:/saved_pages").listFiles((dir, name) -> name.endsWith(".htm") || name.endsWith(".html"))) {
            String pageName = file.getName();
            pageName = pageName.substring(0, pageName.lastIndexOf('.'));
            System.out.println("============================\nProcessing page " + pageName);

            SavedPageProvider provider = new SavedPageProvider(file);

            for (String link : provider.getSources()) {
                app.processUrl(pageName, link);
            }
        }
    }

    private static class StreamGobbler implements Runnable {
        private InputStream inputStream;
        private Consumer<String> consumer;

        public StreamGobbler(InputStream inputStream, Consumer<String> consumer) {
            this.inputStream = inputStream;
            this.consumer = consumer;
        }

        @Override
        public void run() {
            new BufferedReader(new InputStreamReader(inputStream)).lines()
                    .forEach(consumer);
        }
    }

    public App(Properties appProperties, ReqHeaderConfigReader reqHeadersConfig)
    {
        this.appProperties = appProperties;
        this.reqHeaderConfig = reqHeadersConfig;
    }

    public void processUrl(String prefix, String url) throws Exception {
        // читаем iframe, вытаскиваем .mpd
        System.out.println("Parsing source at " + url);

        String body = makeRequestWithHeadersAndGetBody(url, "player");

        HtmlPlayerParser playerParser = HtmlPlayerParser.parseBody(body);

        System.out.println("Processing video: " + playerParser.title());

        String mpd = makeRequestWithHeadersAndGetBody(playerParser.mpd(), "mpd");

        MpdParser mpdData = MpdParser.parseXml(mpd);


        String tmpDir = appProperties.getProperty("dirs.tmp");

        Path tmpVideo = Path.of(tmpDir, "video.mp4");
        Path tmpAudio = Path.of(tmpDir, "audio.mp4");

        System.out.println("Transferring video data...");
        saveFileMultipart("mp4", mpdData.videoUrls(), tmpVideo);

        System.out.println("Transferring audio data...");
        saveFileMultipart("mp4", mpdData.audioUrls(), tmpAudio);


        String ffDir = appProperties.getProperty("dirs.ffmpeg");
        String exe = Path.of(ffDir, "ffmpeg").toString();

        Path finalName = Path.of(appProperties.getProperty("dirs.output"), prefix, playerParser.title() + ".mkv");
        Files.createDirectories(finalName.getParent());

        Files.deleteIfExists(finalName);

        System.out.println("Starting ffmpeg...");
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(exe, "-i", "video.mp4", "-i", "audio.mp4", "-c", "copy", finalName.toString());
        builder.directory(new File(tmpDir));
        Process process = builder.start();

        StreamGobbler streamGobbler =
                new StreamGobbler(process.getErrorStream(), System.out::println);
        Future<?> future = ForkJoinPool.commonPool().submit(streamGobbler);

        process.waitFor();
        future.get(10, TimeUnit.SECONDS);

        System.out.println("Cleanup...");
        Files.delete(tmpVideo);
        Files.delete(tmpAudio);

        System.out.println("Done");
    }

    public void saveFileMultipart(String headerSection, List<String> urls, Path filename) throws URISyntaxException, IOException, InterruptedException {
        int cc = 1;
        for (String videoUrl : urls) {
            System.out.println("  segment " + cc + " of " + urls.size() + " ...");
            makeRequestWithHeadersAndSaveFile(videoUrl, headerSection, filename, cc > 1);
            cc++;
        }
    }


    public String makeRequestWithHeadersAndGetBody(String url, String headerSection) throws URISyntaxException, IOException, InterruptedException {
        InputStream inputStream = makeRequestWithHeaders(url, headerSection);
        try(inputStream)
        {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public void makeRequestWithHeadersAndSaveFile(String url, String headerSection, Path fileName, boolean append) throws URISyntaxException, IOException, InterruptedException {
        try(InputStream is = makeRequestWithHeaders(url, headerSection);FileOutputStream fos = new FileOutputStream(fileName.toFile(), append)) {
            FileChannel fileChannel = fos.getChannel();
            ReadableByteChannel readableByteChannel = Channels.newChannel(is);
            //fileChannel.position(fileChannel.size());
            fileChannel.transferFrom(readableByteChannel, fileChannel.position(), Long.MAX_VALUE);
        }
    }

    private InputStream makeRequestWithHeaders(String url, String headerSection) throws URISyntaxException, IOException, InterruptedException {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(new URI(url));
        Map<String, String> reqHeaders = reqHeaderConfig.getSection(headerSection);
        if (reqHeaders == null) {
            System.out.println("No headers specified for requests: " + headerSection);
        } else {
            for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                reqBuilder.setHeader(e.getKey(), e.getValue());
            }
        }

        HttpResponse<InputStream> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream());
        List<String> values = response.headers().allValues(HEADER_CONTENT_ENCODING);

        return values.contains("gzip") ? new GZIPInputStream(response.body()) : response.body();
    }


    static Properties getAppProperties() throws IOException {
        String value = System.getProperty(APP_CONFIG_PATH_PARAMETER);
        Path configPath;
        if (value != null && !value.isBlank()) {
            configPath = Path.of(value, APP_PROPERTIES_FILENAME);
        } else {
            configPath = Path.of(APP_PROPERTIES_FILENAME);
        }

        Properties prop = new Properties();
        try (FileInputStream fis = new FileInputStream(configPath.toFile())) {
            prop.load(fis);
            return prop;
        }
    }
}