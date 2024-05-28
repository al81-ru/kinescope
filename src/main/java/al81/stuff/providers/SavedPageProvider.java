package al81.stuff.providers;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SavedPageProvider implements SourceProvider {
    private final static Pattern REGEX_IFRAME_KINESCOPE = Pattern.compile("<iframe[^>]* src=\"([^\"]*kinescope.io[^\"]*)\"", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private final List<String> sources = new ArrayList<>();

    public SavedPageProvider(File file) throws IOException {
        String body;
        try(InputStream is = new FileInputStream(file)) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        Matcher m = REGEX_IFRAME_KINESCOPE.matcher(body);
        while (m.find()) {
            if (!sources.contains(m.group(1))) {
                sources.add(m.group(1));
            }
        }
    }

    public SavedPageProvider(String filename) throws IOException {
        this(new File(filename));
    }

    @Override
    public List<String> getSources() {
        return sources;
    }
}
