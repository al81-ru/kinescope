package al81.stuff.parsers;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

public record MpdParser(List<String> videoUrls, List<String> audioUrls) {
    public static MpdParser parseXml(String xmlBody) throws ParserConfigurationException, IOException, SAXException, XPathExpressionException {
        List<String> videoUrls = null;
        List<String> audioUrls = null;

        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = builderFactory.newDocumentBuilder();
        InputSource is = new InputSource();
        is.setCharacterStream(new StringReader(xmlBody));
        Document xmlDocument = builder.parse(is);
        XPath xPath = XPathFactory.newInstance().newXPath();
        String expression = "/MPD/Period/AdaptationSet[@mimeType='video/mp4']";
        NodeList video = (NodeList) xPath.compile(expression).evaluate(xmlDocument, XPathConstants.NODESET);
        if (video.getLength() > 0)
        {
            Node item = video.item(0);
            String maxWidth = item.getAttributes().getNamedItem("maxWidth").getNodeValue();

            Node presentation = (Node) xPath.compile("Representation[@width=" + maxWidth + "]").evaluate(item, XPathConstants.NODE);
            if (presentation == null)
            {
                throw new RuntimeException("Presentation not found");
            }

            videoUrls = getRepresentationSegmentUrls(xPath, presentation);
        }


        expression = "/MPD/Period/AdaptationSet[@mimeType='audio/mp4']/Representation";
        Node audioPresentation = (Node) xPath.compile(expression).evaluate(xmlDocument, XPathConstants.NODE);
        if (audioPresentation != null)
        {
            audioUrls = getRepresentationSegmentUrls(xPath, audioPresentation);
        }
        else {
            System.out.println("Audio not found");
        }

        return new MpdParser(videoUrls, audioUrls);
    }

    private static List<String> getRepresentationSegmentUrls(XPath xPath, Node representationNode) throws XPathExpressionException {
        List<String> urls = new ArrayList<>();
        Set<String> keys = new HashSet<>();

        Node initNode = (Node) xPath.compile("SegmentList/Initialization").evaluate(representationNode, XPathConstants.NODE);
        if (initNode != null) {
            urls.add(initNode.getAttributes().getNamedItem("sourceURL").getNodeValue());
        }

        NodeList nodes = (NodeList) xPath.compile("SegmentList/SegmentURL").evaluate(representationNode, XPathConstants.NODESET);
        IntStream.range(0, nodes.getLength()).mapToObj(nodes::item)
                .map(node -> node.getAttributes().getNamedItem("media").getNodeValue()).distinct().forEach(urls::add);

        List<String> res = new ArrayList<>();

        for (String val : urls) {
            String key = val.substring(0, val.indexOf('?'));
            if (keys.contains(key)) {
                continue;
            }

            keys.add(key);
            res.add(val);
        }

        return res;
    }
}
