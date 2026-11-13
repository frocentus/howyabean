package org.openntf.xsp.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Externalizable;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import lotus.domino.Base;
import lotus.domino.Database;
import lotus.domino.DateRange;
import lotus.domino.DateTime;
import lotus.domino.Document;
import lotus.domino.DocumentCollection;
import lotus.domino.Item;
import lotus.domino.MIMEEntity;
import lotus.domino.MIMEHeader;
import lotus.domino.NoteCollection;
import lotus.domino.NotesException;
import lotus.domino.Session;
import lotus.domino.Stream;

import org.openntf.xsp.util.codec.ValueCodec;
import org.openntf.xsp.util.codec.ValueCodecs;

import com.ibm.xsp.extlib.util.ExtLibUtil;

/*
 * Nearly all of this class borrows heavily from:
 * https://github.com/OpenNTF/org.openntf.domino/blob/master/org.openntf.domino/src/org/openntf/domino/utils/DominoUtils.java
 */
public class DominoUtil {
	/**
	 * Ceiling for a stored value once it has been decompressed. A compressed payload is
	 * cheap to store and expensive to expand, so the limit is applied while reading rather
	 * than to the bytes as they sit in the document.
	 */
	private static final int MAX_STORED_BYTES = 4 * 1024 * 1024;

	@SuppressWarnings("unchecked")
	public static void incinerate(final Object... objects) {
		for (Object eachObject : objects) {
			if (eachObject != null) {
				if (eachObject instanceof Base) {
					Base dominoObject = (Base) eachObject;
					try {
						dominoObject.recycle();
					} catch (NotesException e) {
						// already recycled: nothing left to release
					}
				} else if (eachObject instanceof Map) {
					Set<Map.Entry> entries = ((Map) eachObject).entrySet();
					for (Map.Entry<?, ?> entry : entries) {
						incinerate(entry.getKey(), entry.getValue());
					}
				} else if (eachObject instanceof Collection) {
					Iterator i = ((Collection) eachObject).iterator();
					while (i.hasNext()) {
						Object obj = i.next();
						incinerate(obj);
					}
				} else if (eachObject.getClass().isArray()) {
					try {
						Object[] objs = (Object[]) eachObject;
						for (Object ao : objs) {
							incinerate(ao);
						}
					} catch (Throwable t) {
						// who cares?
					}
				}
			}
		}
	}

	/**
	 * Reads a header of a MIME part and hands the header handle straight to the scope, so
	 * no caller ends up holding a Domino object it has not accounted for.
	 */
	public static String headerValue(final MIMEEntity entity, final String name, final Recycle parts) throws NotesException {
		MIMEHeader header = parts.own(entity.getNthHeader(name));
		return header == null ? null : header.getHeaderVal();
	}

	/** Sets a header, creating it first if the part does not have it yet. */
	private static void setHeader(final MIMEEntity entity, final String name, final String value, final Recycle parts)
			throws NotesException {
		MIMEHeader header = parts.own(entity.getNthHeader(name));
		if (header == null) {
			header = parts.own(entity.createHeader(name));
		}
		header.setHeaderVal(value);
	}

	private static ObjectInputStream objectStream(final byte[] payload) throws IOException {
		return new ObjectInputStream(new ByteArrayInputStream(payload));
	}

	/** The bytes of a MIME part, owned by the scope. */
	private static byte[] readPayload(final Session session, final MIMEEntity entity, final Recycle parts) throws NotesException {
		Stream mimeStream = parts.own(session.createStream());
		entity.getContentAsBytes(mimeStream);
		ByteArrayOutputStream contents = new ByteArrayOutputStream();
		mimeStream.getContents(contents);
		return contents.toByteArray();
	}

	/**
	 * Expands a compressed payload, refusing anything that grows past
	 * {@link #MAX_STORED_BYTES}. A stored value is data from a document, and a document can
	 * replicate in from anywhere, so "it says gzip" is not a reason to copy an unbounded
	 * number of bytes into memory.
	 */
	private static byte[] inflate(final byte[] payload, final String contentEncoding) throws IOException {
		if (contentEncoding == null || !contentEncoding.toLowerCase(Locale.ENGLISH).contains("gzip")) {
			return payload;
		}
		try (InputStream compressed = new GZIPInputStream(new ByteArrayInputStream(payload));
				ByteArrayOutputStream result = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = compressed.read(buffer)) > 0) {
				if (result.size() + read > MAX_STORED_BYTES) {
					throw new IOException("The stored value expands to more than " + MAX_STORED_BYTES
							+ " bytes; refusing to read it");
				}
				result.write(buffer, 0, read);
			}
			return result.toByteArray();
		}
	}

	public static Document next(final Document current, final DocumentCollection collection) {
		Document result = null;
		try {
			result = collection.getNextDocument(current);
		} catch (NotesException e) {
			LogUtil.trace(e);
		} finally {
			incinerate(current);
		}
		return result;
	}

	/**
	 * Whether an item holds a value this class wrote, as opposed to a MIME part that belongs
	 * to the document (a body, an attachment). Both readers and writers ask this, so the two
	 * cannot disagree about what "a stored object" is.
	 */
	public static boolean isStoredObject(final MIMEEntity entity, final Recycle parts) throws NotesException {
		if (ValueCodecs.byScheme(headerValue(entity, "X-Storage-Scheme", parts)) != null) {
			return true;
		}
		String contentType = headerValue(entity, "Content-Type", parts);
		return "application/x-java-serialized-object".equals(contentType)
				|| "application/x-java-externalized-object".equals(contentType);
	}

	/**
	 * Reads a value this class stored: either a payload of one of the configured
	 * {@link ValueCodecs}, or - for documents written before there were codecs - a Java
	 * object stream.
	 */
	public static Object restoreState(final Document doc, final String itemName) throws Exception {
		Session session = ExtLibUtil.getCurrentSession();
		/*
		 * Saved objects are MIME parts, so conversion has to be off for the whole block -
		 * MimeScope does that and restores the previous setting on every exit path, and
		 * Recycle releases the parts. Closing happens in reverse declaration order, i.e.
		 * the parts are recycled while conversion is still off, and only then is the
		 * session put back.
		 */
		try (MimeScope mime = MimeScope.off(session); Recycle parts = Recycle.of()) {
			MIMEEntity entity = parts.own(doc.getMIMEEntity(itemName));
			if (entity == null) {
				return null;
			}
			/*
			 * The description headers are read up front so that the scope owns them even
			 * when reading fails halfway through.
			 */
			String storageScheme = headerValue(entity, "X-Storage-Scheme", parts);
			byte[] payload = inflate(readPayload(session, entity, parts), headerValue(entity, "Content-Encoding", parts));
			ValueCodec codec = ValueCodecs.byScheme(storageScheme);
			if (codec != null) {
				/*
				 * The payload is data, not a description of classes to load: whatever it
				 * says about types is resolved against the registered structured types,
				 * and everything else is a map, a list or a scalar.
				 */
				return codec.decode(payload);
			}
			return restoreJavaState(doc, entity, parts, payload, storageScheme);
		}
	}

	/**
	 * The legacy reader for Java-serialized values. Kept so that documents written by the
	 * previous version stay readable; nothing writes new values in these formats, and the
	 * method is the reason to finish migrating rather than a design to copy.
	 */
	@SuppressWarnings("unchecked")
	private static Object restoreJavaState(final Document doc, final MIMEEntity entity, final Recycle parts, final byte[] payload,
			final String storageSchemeValue) throws Exception {
		Object result = null;
		try (ObjectInputStream objectStream = objectStream(payload)) {
			String originalJavaClassValue = headerValue(entity, "X-Original-Java-Class", parts);
			String javaClassName = headerValue(entity, "X-Java-Class", parts);
			// There are three potential storage forms: Externalizable,
			// Serializable, and StateHolder, distinguished by type or header
			if ("x-java-externalized-object".equals(entity.getContentSubType())) {
				Class<Externalizable> externalizableClass = (Class<Externalizable>) Class.forName(javaClassName);
				Externalizable restored = externalizableClass.newInstance();
				restored.readExternal(objectStream);
				result = restored;
			} else {
				Object restored = objectStream.readObject();
				// But wait! It might be a StateHolder object or Collection!
				if ("StateHolder".equals(storageSchemeValue)) {
					Class<?> facesContextClass = Class.forName("javax.faces.context.FacesContext");
					Method getCurrentInstance = facesContextClass.getMethod("getCurrentInstance");
					Class<?> stateHoldingClass = Class.forName(originalJavaClassValue);
					Method restoreStateMethod = stateHoldingClass.getMethod("restoreState", facesContextClass, Object.class);
					result = stateHoldingClass.newInstance();
					restoreStateMethod.invoke(result, getCurrentInstance.invoke(null), restored);
				} else if (originalJavaClassValue != null && originalJavaClassValue.endsWith(".domino.DocumentCollection")) {
					/*
					 * WARNING: Nathan says this bit might still be buggy...
					 */
					try {
						String[] unids = (String[]) restored;
						Database db = doc.getParentDatabase();
						DocumentCollection docCollection = db.createDocumentCollection();
						for (String unid : unids) {
							docCollection.addDocument(db.getDocumentByUNID(unid));
						}
						result = docCollection;
					} catch (Exception e) {
						LogUtil.trace(e);
					}
				} else if (originalJavaClassValue != null && originalJavaClassValue.endsWith(".domino.NoteCollection")) {
					String[] unids = (String[]) restored;
					Database db = doc.getParentDatabase();
					NoteCollection noteCollection = db.createNoteCollection(false);
					for (String unid : unids) {
						noteCollection.add(db.getDocumentByUNID(unid));
					}
					result = noteCollection;
				} else {
					result = restored;
				}
			}
		}
		return result;
	}

	public static void saveState(final Serializable object, final Document doc, final String itemName) throws Exception {
		saveState(object, doc, itemName, true, null);
	}

	public static void saveState(final Serializable object, final Document doc, final String itemName, final boolean compress,
			final Map<String, String> headers) throws Exception {
		if (object == null) {
			LogUtil.debug("Ignoring attempt to save MIMEBean value of null");
			return;
		}
		Session session = ExtLibUtil.getCurrentSession();
		ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
		boolean externalized = object instanceof Externalizable;
		String contentType = externalized ? "application/x-java-externalized-object" : "application/x-java-serialized-object";
		// The object is written to memory first, so the MIME scope covers only the document
		try (ObjectOutputStream objectStream = new ObjectOutputStream(compress ? new GZIPOutputStream(byteStream) : byteStream)) {
			if (externalized) {
				((Externalizable) object).writeExternal(objectStream);
			} else {
				objectStream.writeObject(object);
			}
			objectStream.flush();
		}
		try (MimeScope mime = MimeScope.off(session); Recycle parts = Recycle.of()) {
			Stream mimeStream = parts.own(session.createStream());
			MIMEEntity entity = parts.own(doc.getMIMEEntity(itemName));
			if (entity == null) {
				Item itemChk = parts.own(doc.getFirstItem(itemName));
				if (itemChk != null) {
					itemChk.remove();
				}
				entity = parts.own(doc.createMIMEEntity(itemName));
			}
			mimeStream.setContents(new ByteArrayInputStream(byteStream.toByteArray()));
			entity.setContentFromBytes(mimeStream, contentType, MIMEEntity.ENC_NONE);
			MIMEHeader javaClass = parts.own(entity.getNthHeader("X-Java-Class"));
			if (javaClass == null) {
				javaClass = parts.own(entity.createHeader("X-Java-Class"));
			}
			try {
				javaClass.setHeaderVal(object.getClass().getName());
			} catch (NotesException e) {
				LogUtil.trace(e);
			}
			MIMEHeader contentEncoding = parts.own(entity.getNthHeader("Content-Encoding"));
			if (compress) {
				if (contentEncoding == null) {
					contentEncoding = parts.own(entity.createHeader("Content-Encoding"));
				}
				contentEncoding.setHeaderVal("gzip");
			} else if (contentEncoding != null) {
				contentEncoding.remove();
			}
			if (headers != null) {
				for (Map.Entry<String, String> entry : headers.entrySet()) {
					setHeader(entity, entry.getKey(), entry.getValue(), parts);
				}
			}
		}
	}

	/**
	 * Writes an encoded value as a MIME part, the same shape a saved object had, with the
	 * scheme and the content type telling the reader which codec produced it. The original
	 * type is recorded for diagnosis only; nothing is ever instantiated from the header.
	 */
	public static void saveEncoded(final Document doc, final String itemName, final ValueCodecs.Encoded encoded) throws Exception {
		Session session = ExtLibUtil.getCurrentSession();
		try (MimeScope mime = MimeScope.off(session); Recycle parts = Recycle.of()) {
			Stream mimeStream = parts.own(session.createStream());
			MIMEEntity entity = parts.own(doc.getMIMEEntity(itemName));
			if (entity == null) {
				/*
				 * A native item of the same name would be shadowed by the MIME part, so it
				 * goes first: the document keeps exactly one representation per item.
				 */
				Item itemChk = parts.own(doc.getFirstItem(itemName));
				if (itemChk != null) {
					itemChk.remove();
				}
				entity = parts.own(doc.createMIMEEntity(itemName));
			}
			mimeStream.setContents(new ByteArrayInputStream(encoded.payload()));
			entity.setContentFromBytes(mimeStream, encoded.contentType(), MIMEEntity.ENC_NONE);
			setHeader(entity, "X-Storage-Scheme", encoded.scheme(), parts);
			setHeader(entity, "X-Original-Java-Class", encoded.type(), parts);
		}
	}

	protected static Vector<Object> toDominoFriendly(final Collection<?> values, final Document context) throws IllegalArgumentException {
		Vector<Object> result = new Vector<>();
		for (Object value : values) {
			result.add(toDominoFriendly(value));
		}
		return result;
	}

	public static Object toDominoFriendly(final Object value) {
		/*
		 * A weak subset of the auto-conversion done by the OpenNTF Domino API: http
		 * ://github.com/OpenNTF/org.openntf.domino/blob/master/org.openntf. domino
		 */
		if (value == null) {
			/*
			 * Null means "no value", which is not the same thing as an empty string.
			 * Callers remove the item in this case instead of storing "".
			 */
			return null;
		}
		if (value instanceof DateTime || value instanceof DateRange || value instanceof Item) {
			// already safe to store
			return value;
		} else if (value instanceof Integer || value instanceof Double || value instanceof String) {
			// already safe to store
			return value;
		} else if (value instanceof Boolean) {
			return (Boolean) value ? "1" : "0";
		}
		// Now for the illegal-but-convertible types
		if (value instanceof Number) {
			return ((Number) value).doubleValue();
		} else if (value instanceof Date || value instanceof Calendar) {
			Session session = ExtLibUtil.getCurrentSession();
			try {
				return value instanceof Date ? session.createDateTime((Date) value) : session.createDateTime((Calendar) value);
			} catch (NotesException e) {
				LogUtil.trace(e);
				return null;
			}
		} else if (value instanceof CharSequence) {
			return value.toString();
		}
		throw new IllegalArgumentException("Cannot convert to Domino friendly from type " + value.getClass().getName());
	}
}
