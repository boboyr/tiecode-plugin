package studio.basic.lang.xml.tree.parser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;

import studio.basic.lang.xml.tree.XmlDocument;

public class XmlTreeParser {
    private XmlPullParser parser;

    public XmlTreeParser() throws XmlPullParserException {
        this.parser = XmlPullParserFactory.newInstance().newPullParser();
    }

    public XmlDocument parseXml(File file) throws XmlPullParserException, IOException {
        parser.setInput(new FileReader(file));
        return parseInternal();
    }
    public XmlDocument parseXml(String text) throws XmlPullParserException, IOException {
        parser.setInput(new StringReader(text));
        return parseInternal();
    }

    private XmlDocument parseInternal() throws XmlPullParserException, IOException {
        int type;
        while ((type = parser.getEventType()) != XmlPullParser.END_DOCUMENT) {
            switch (type) {
                case XmlPullParser.START_DOCUMENT: {
                    System.out.println("====start");
                    int count = parser.getAttributeCount();
                    for (int i = 0; i < count; i++) {
                        String namespace = parser.getAttributeNamespace(i);
                        String name = parser.getAttributeName(i);
                        String value = parser.getAttributeValue(i);
                        System.out.printf("%s:%s=%s\n", namespace, name, value);
                    }
                    break;
                }
                case XmlPullParser.START_TAG: {
                    System.out.println("====start-tag");
                    System.out.println(parser.getPrefix());
                    int count = parser.getAttributeCount();
                    for (int i = 0; i < count; i++) {
                        System.out.printf("line: %d, column: %d", parser.getLineNumber(), parser.getColumnNumber());
                        String namespace = parser.getAttributeNamespace(i);
                        String name = parser.getAttributeName(i);
                        String value = parser.getAttributeValue(i);
                        System.out.printf("%s:%s=%s\n", namespace, name, value);
                    }
                    break;
                }
                case XmlPullParser.END_TAG:
                    System.out.println("====end-tag");
                    break;
                case XmlPullParser.TEXT:
                    System.out.println("====text");
                    System.out.println(parser.getText());
                    break;
                case XmlPullParser.CDSECT:
                    System.out.println("====CDATA");
                    break;
                case XmlPullParser.ENTITY_REF:
                    System.out.println("====ref");
                    break;
                case XmlPullParser.IGNORABLE_WHITESPACE:
                    System.out.println("====space");
                    break;
                case XmlPullParser.PROCESSING_INSTRUCTION:
                    System.out.println("====ins");
                    break;
                case XmlPullParser.COMMENT:
                    System.out.println("====comment");
                    break;
                case XmlPullParser.DOCDECL:
                    System.out.println("====docdata");
                    break;
            }
            parser.next();
        }
        return null;
    }
}
