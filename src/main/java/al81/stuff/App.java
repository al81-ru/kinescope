package al81.stuff;

import al81.stuff.misc.ReqHeaderConfigReader;
import al81.stuff.parsers.HtmlPlayerParser;
import al81.stuff.parsers.MpdParser;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
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
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

public class App {
    private static final String APP_CONFIG_PATH_PARAMETER = "configDir";
    private static final String APP_PROPERTIES_FILENAME = "app.properties";

    private static final String HEADER_CONTENT_ENCODING = "Content-Encoding";

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final Properties appProperties;

    private final ReqHeaderConfigReader reqHeaderConfig;



    public static void main(String[] args) throws IOException, URISyntaxException, InterruptedException, XPathExpressionException, ParserConfigurationException, SAXException, ExecutionException, TimeoutException {
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

        if (args.length == 0) {
            System.out.println("Specify embed player url");
            System.exit(1);
        }

        App app = new App(properties, reqHeaderConfigReader);

        String prefix = args[0];
        for (int i = 1; i < args.length; i++) {
            app.process(prefix, args[i]);
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

    public void process( String prefix, String url) throws URISyntaxException, IOException, InterruptedException, XPathExpressionException, ParserConfigurationException, SAXException, ExecutionException, TimeoutException {
        // читаем iframe, вытаскиваем .mpd
        System.out.println("Parsing source at " + url);

        String body = makeRequestWithHeadersAndGetBody(url, "player");

        HtmlPlayerParser playerParser = HtmlPlayerParser.parseBody(body);

        System.out.println("Processing video: " + playerParser.title());

        String mpd = makeRequestWithHeadersAndGetBody(playerParser.mpd(), "mpd");

        MpdParser mpdData = MpdParser.parseXml(mpd);


        String tmpDir = appProperties.getProperty("dirs.tmp");

        System.out.println("Transferring video data...");
        makeRequestWithHeadersAndSaveFile(mpdData.videoUrl(), "mp4", Path.of(tmpDir, "video.mp4"));
        System.out.println("Transferring audio data...");
        makeRequestWithHeadersAndSaveFile(mpdData.audioUrl(), "mp4", Path.of(tmpDir, "audio.mp4"));

        String ffDir = appProperties.getProperty("dirs.ffmpeg");
        String exe = Path.of(ffDir, "ffmpeg").toString();

        Path finalName = Path.of(appProperties.getProperty("dirs.output"), prefix, playerParser.title() + ".mkv");
        Files.createDirectories(finalName.getParent());

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
        Files.delete(Path.of(tmpDir, "video.mp4"));
        Files.delete(Path.of(tmpDir, "audio.mp4"));

        System.out.println("Done");
    }


    public String makeRequestWithHeadersAndGetBody(String url, String headerSection) throws URISyntaxException, IOException, InterruptedException {
        InputStream inputStream = makeRequestWithHeaders(url, headerSection);
        try(inputStream)
        {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public void makeRequestWithHeadersAndSaveFile(String url, String headerSection, Path fileName) throws URISyntaxException, IOException, InterruptedException {
        try(InputStream is = makeRequestWithHeaders(url, headerSection);FileOutputStream fos = new FileOutputStream(fileName.toFile())) {
            FileChannel fileChannel = fos.getChannel();
            ReadableByteChannel readableByteChannel = Channels.newChannel(is);
            fileChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
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