// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.tools;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.ValidatorHandler;

import org.openstreetmap.josm.io.CachedFile;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.XMLFilterImpl;

/**
 * An helper class that reads from a XML stream into specific objects.
 *
 * @author Imi
 */
public class XmlObjectParser implements Iterable<Object> {
    /**
     * The language prefix to use
     */
    public static final String lang = LanguageInfo.getLanguageCodeXML();

    private static class AddNamespaceFilter extends XMLFilterImpl {

        private final String namespace;

        AddNamespaceFilter(String namespace) {
            this.namespace = namespace;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
            if ("".equals(uri)) {
                super.startElement(namespace, localName, qName, atts);
            } else {
                super.startElement(uri, localName, qName, atts);
            }
        }
    }

    private final class Parser extends DefaultHandler {
        private final Stack<Object> current = new Stack<>();
        private StringBuilder characters = new StringBuilder(64);
        private Locator locator;
        private final StringParser primitiveParsers = new StringParser(StringParser.DEFAULT)
                .registerParser(boolean.class, this::parseBoolean)
                .registerParser(Boolean.class, this::parseBoolean);

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        void throwException(Exception e) throws XmlParsingException {
            throw new XmlParsingException(e).rememberLocation(locator);
        }

        @Override
        public void startElement(String ns, String lname, String qname, Attributes a) throws SAXException {
            final Entry entry = mapping.get(qname);
            if (entry != null) {
                Class<?> klass = entry.klass;
                try {
                    current.push(klass.getConstructor().newInstance());
                } catch (ReflectiveOperationException e) {
                    throwException(e);
                }
                for (int i = 0; i < a.getLength(); ++i) {
                    setValue(entry, a.getQName(i), a.getValue(i));
                }
                if (entry.onStart) {
                    report();
                }
                if (entry.both) {
                    queue.add(current.peek());
                }
            }
        }

        @Override
        public void endElement(String ns, String lname, String qname) throws SAXException {
            final Entry entry = mapping.get(qname);
            if (entry != null && !entry.onStart) {
                report();
            } else if (entry != null && characters != null && !current.isEmpty()) {
                setValue(entry, qname, characters.toString().trim());
                characters = new StringBuilder(64);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            characters.append(ch, start, length);
        }

        private void report() {
            queue.add(current.pop());
            characters = new StringBuilder(64);
        }

        private void setValue(Entry entry, String fieldName, String value0) throws SAXException {
            final String value = value0 != null ? value0.intern() : null;
            CheckParameterUtil.ensureParameterNotNull(entry, "entry");
            if ("class".equals(fieldName) || "default".equals(fieldName) || "throw".equals(fieldName) ||
                    "new".equals(fieldName) || "null".equals(fieldName)) {
                fieldName += '_';
            }
            fieldName = fieldName.replace(':', '_');
            try {
                Object c = current.peek();
                Field f = entry.getField(fieldName);
                if (f == null && fieldName.startsWith(lang)) {
                    f = entry.getField("locale_" + fieldName.substring(lang.length()));
                }
                Optional<?> parsed = Optional.ofNullable(f)
                        .filter(field -> Modifier.isPublic(field.getModifiers()))
                        .flatMap(field -> primitiveParsers.tryParse(field.getType(), value));
                if (parsed.isPresent()) {
                    f.set(c, parsed.get());
                } else {
                    String setter;
                    if (fieldName.startsWith(lang)) {
                        int l = lang.length();
                        setter = "set" + fieldName.substring(l, l + 1).toUpperCase(Locale.ENGLISH) + fieldName.substring(l + 1);
                    } else {
                        setter = "set" + fieldName.substring(0, 1).toUpperCase(Locale.ENGLISH) + fieldName.substring(1);
                    }
                    Method m = entry.getMethod(setter);
                    if (m != null) {
                        parsed = primitiveParsers.tryParse(m.getParameterTypes()[0], value);
                        m.invoke(c, parsed.isPresent() ? parsed.get() : value);
                    }
                }
            } catch (ReflectiveOperationException | IllegalArgumentException e) {
                Logging.error(e); // SAXException does not dump inner exceptions.
                throwException(e);
            }
        }

        private boolean parseBoolean(String s) {
            return s != null
                    && !"0".equals(s)
                    && !s.startsWith("off")
                    && !s.startsWith("false")
                    && !s.startsWith("no");
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throwException(e);
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throwException(e);
        }
    }

    private static class Entry {
        private final Class<?> klass;
        private final boolean onStart;
        private final boolean both;
        private final Map<String, Field> fields = new HashMap<>();
        private final Map<String, Method> methods = new HashMap<>();
        /** This is used to avoid array copies in {@link #getUncachedMethod(String)}. Do not modify. */
        private Method[] cachedKlassMethods;
        /** This is used to avoid array copies in {@link #getUncachedField(String)}. Do not modify. */
        private Field[] cachedKlassFields;

        Entry(Class<?> klass, boolean onStart, boolean both) {
            this.klass = klass;
            this.onStart = onStart;
            this.both = both;
        }

        Field getField(String s) {
            return fields.computeIfAbsent(s, this::getUncachedField);
        }

        /**
         * Get a field (uncached in {@link #fields})
         * @implNote Please profile startup when changing
         * @param s The field to get
         * @return The field, or {@code null}.
         */
        private Field getUncachedField(String s) {
            if (this.cachedKlassFields == null) {
                this.cachedKlassFields = klass.getFields();
            }
            for (Field field : this.cachedKlassFields) {
                if (field.getName().equals(s)) {
                    return field;
                }
            }
            return null;
        }

        Method getMethod(String s) {
            return methods.computeIfAbsent(s, this::getUncachedMethod);
        }

        /**
         * Get an uncached method (in {@link #methods})
         * @implNote Please profile startup when changing
         * @param s The method to find
         * @return The method or {@code null}.
         */
        private Method getUncachedMethod(String s) {
            if (cachedKlassMethods == null) {
                cachedKlassMethods = klass.getMethods();
            }
            for (Method method : cachedKlassMethods) {
                if (method.getParameterCount() == 1 && method.getName().equals(s)) {
                    return method;
                }
            }
            return null;
        }
    }

    private final Map<String, Entry> mapping = new HashMap<>();
    private final DefaultHandler parser;

    /**
     * The queue of already parsed items from the parsing thread.
     */
    private final List<Object> queue = new LinkedList<>();
    private Iterator<Object> queueIterator;

    /**
     * Constructs a new {@code XmlObjectParser}.
     */
    public XmlObjectParser() {
        parser = new Parser();
    }

    private Iterable<Object> start(final Reader in, final ContentHandler contentHandler) throws SAXException, IOException {
        try {
            XMLReader reader = XmlUtils.newSafeSAXParser().getXMLReader();
            reader.setContentHandler(contentHandler);
            try {
                // Do not load external DTDs (fix #8191)
                reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            } catch (SAXException e) {
                // Exception very unlikely to happen, so no need to translate this
                Logging.log(Logging.LEVEL_ERROR, "Cannot disable 'load-external-dtd' feature:", e);
            }
            reader.parse(new InputSource(in));
            queueIterator = queue.iterator();
            return this;
        } catch (ParserConfigurationException e) {
            throw new JosmRuntimeException(e);
        }
    }

    /**
     * Starts parsing from the given input reader, without validation.
     * @param in The input reader
     * @return iterable collection of objects
     * @throws SAXException if any XML or I/O error occurs
     */
    public Iterable<Object> start(final Reader in) throws SAXException {
        try {
            return start(in, parser);
        } catch (IOException e) {
            throw new SAXException(e);
        }
    }

    /**
     * Starts parsing from the given input reader, with XSD validation.
     * @param in The input reader
     * @param namespace default namespace
     * @param schemaSource XSD schema
     * @return iterable collection of objects
     * @throws SAXException if any XML or I/O error occurs
     */
    public Iterable<Object> startWithValidation(final Reader in, String namespace, String schemaSource) throws SAXException {
        SchemaFactory factory = XmlUtils.newXmlSchemaFactory();
        try (CachedFile cf = new CachedFile(schemaSource); InputStream mis = cf.getInputStream()) {
            Schema schema = factory.newSchema(new StreamSource(mis));
            ValidatorHandler validator = schema.newValidatorHandler();
            validator.setContentHandler(parser);
            validator.setErrorHandler(parser);

            AddNamespaceFilter filter = new AddNamespaceFilter(namespace);
            filter.setContentHandler(validator);
            return start(in, filter);
        } catch (IOException e) {
            throw new SAXException(tr("Failed to load XML schema."), e);
        }
    }

    /**
     * Add a new tag name to class type mapping
     * @param tagName The tag name that should be converted to that class
     * @param klass The class the XML elements should be converted to.
     */
    public void map(String tagName, Class<?> klass) {
        mapping.put(tagName, new Entry(klass, false, false));
    }

    public void mapOnStart(String tagName, Class<?> klass) {
        mapping.put(tagName, new Entry(klass, true, false));
    }

    public void mapBoth(String tagName, Class<?> klass) {
        mapping.put(tagName, new Entry(klass, false, true));
    }

    /**
     * Get the next element that was parsed
     * @return The next object
     */
    public Object next() {
        return queueIterator.next();
    }

    /**
     * Check if there is a next parsed object available
     * @return <code>true</code> if there is a next object
     */
    public boolean hasNext() {
        return queueIterator.hasNext();
    }

    @Override
    public Iterator<Object> iterator() {
        return queue.iterator();
    }
}
