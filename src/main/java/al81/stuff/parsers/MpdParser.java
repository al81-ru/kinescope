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

public record MpdParser(String videoUrl, String videoRange, String audioUrl, String audioRange) {
    public static MpdParser parseXml(String xmlBody) throws ParserConfigurationException, IOException, SAXException, XPathExpressionException {
        String videoUrl = null, videoRange = null, audioUrl = null, audioRange = null;

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

            Node urlNode = (Node) xPath.compile("SegmentList/Initialization").evaluate(presentation, XPathConstants.NODE);
            videoUrl = urlNode.getAttributes().getNamedItem("sourceURL").getNodeValue();
            videoRange = urlNode.getAttributes().getNamedItem("range").getNodeValue();
        }


        expression = "/MPD/Period/AdaptationSet[@mimeType='audio/mp4']/Representation/SegmentList/Initialization";
        Node audio = (Node) xPath.compile(expression).evaluate(xmlDocument, XPathConstants.NODE);
        if (audio != null)
        {
            audioUrl = audio.getAttributes().getNamedItem("sourceURL").getNodeValue();
            audioRange = audio.getAttributes().getNamedItem("range").getNodeValue();
        }
        else {
            System.out.println("Audio not found");
        }

        return new MpdParser(videoUrl, videoRange, audioUrl, audioRange);
    }
}
