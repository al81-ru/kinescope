package al81.stuff.misc;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ReqHeaderConfigReader {
    private final Map<String, Map<String, String>> sections;

    //private static final Pattern SECTION_REGEX = Pattern.compile("^\\[(.*)]$");

    private ReqHeaderConfigReader(Map<String, Map<String, String>> sections) {
        this.sections = sections;
    }

    public static ReqHeaderConfigReader loadReader(Path filePath) throws IOException {
        Map<String, Map<String, String>> res = new HashMap<>();
        Map<String, String> currentSection = null;
        String name = null;

        try (FileReader fr = new FileReader(filePath.toFile()); BufferedReader sr = new BufferedReader(fr)) {
            String line;

            while ((line = sr.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                /*
                Matcher m = SECTION_REGEX.matcher(line);
                if (m.find()) {
                    String newSection = m.group();
                }
                */

                if (line.startsWith("[") && line.endsWith("]")) {
                    String newSectionName = line.substring(1, line.length() - 1);
                    if (newSectionName.equals(name)) {
                        throw new RuntimeException("Duplicated section name " + newSectionName + "in request-headers configuration");
                    }
                    if (currentSection != null) {
                        res.put(name, currentSection);
                    }

                    name = newSectionName;
                } else {
                    if (name != null) {
                        if (currentSection == null) {
                            currentSection = new HashMap<>();
                        }

                        int splitIndex = line.indexOf(":");
                        if (splitIndex > 1) {
                            String key = line.substring(0, splitIndex);
                            String value = line.substring(splitIndex + 1).trim();
                            currentSection.put(key, value);
                        }
                    }
                }
            }
        }

        if (currentSection != null) {
            res.put(name, currentSection);
        }

        return new ReqHeaderConfigReader(res);
    }

    public Map<String, String> getSection(String section) {
        return sections.get(section);
    }
}
