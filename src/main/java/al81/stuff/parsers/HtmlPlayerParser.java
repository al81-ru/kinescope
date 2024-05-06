package al81.stuff.parsers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record HtmlPlayerParser(String title, String mpd) {
    private static final Pattern REGEX_TITLE = Pattern.compile("<title>(.*)</title>", Pattern.CASE_INSENSITIVE);
    private static final Pattern REGEX_MPD = Pattern.compile("\"([^\"]*.mpd)\"", Pattern.CASE_INSENSITIVE);


    public static HtmlPlayerParser parseBody(String body){
        String title = null, mpd = null;

        Matcher matcher = REGEX_TITLE.matcher(body);
        if (matcher.find())
        {
            title = matcher.group(1);
        }

        matcher = REGEX_MPD.matcher(body);
        if (matcher.find())
        {
            mpd = matcher.group(1);
        }

        return new HtmlPlayerParser(title, mpd);
    }
}
