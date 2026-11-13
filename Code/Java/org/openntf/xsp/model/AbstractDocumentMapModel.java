package org.openntf.xsp.model;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Vector;

import javax.faces.application.FacesMessage;
import javax.faces.context.FacesContext;

import lotus.domino.Document;
import lotus.domino.DocumentCollection;
import lotus.domino.Item;
import lotus.domino.MIMEEntity;
import lotus.domino.NoteCollection;
import lotus.domino.NotesException;
import lotus.domino.Session;

import org.openntf.xsp.util.DominoUtil;
import org.openntf.xsp.util.LogUtil;
import org.openntf.xsp.util.MimeScope;
import org.openntf.xsp.util.Recycle;
import org.openntf.xsp.util.codec.ValueCodec;
import org.openntf.xsp.util.codec.ValueCodecs;

import com.ibm.commons.util.StringUtil;
import com.ibm.xsp.extlib.util.ExtLibUtil;
import com.ibm.xsp.model.domino.wrapped.DominoDocument;

public abstract class AbstractDocumentMapModel extends AbstractMapModel {
	public static int MAX_NATIVE_VECTOR_SIZE = 255;
	private static final long serialVersionUID = 1L;

	/**
	 * Items that never take part in the map <-> document round trip: either they are
	 * stored by MIME handling itself or they are maintained by Domino.
	 */
	private static final Set<String> IGNORED_ITEMS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
			"MIME_Version", "$NoteHasNativeMIME", "$MIMETrack", "$UpdatedBy", "$Revisions", "$REF", "$Conflict")));

	private String unid;

	public AbstractDocumentMapModel() {
		String documentId = ExtLibUtil.readParameter(FacesContext.getCurrentInstance(), "id");
		if (StringUtil.isNotEmpty(documentId)) {
			load(documentId);
		}
	}

	protected abstract String getFormName();

	/*
	 * Borrowed heavily from
	 * https://github.com/OpenNTF/org.openntf.domino/blob/master/org.openntf.domino/src/org/openntf/domino/impl/Document.java
	 */
	private Object getItemValueMIME(final Document parent, final String name) {
		Session session = ExtLibUtil.getCurrentSession();
		/*
		 * MIME conversion has to be off to read the raw part. MimeScope restores the
		 * previous setting on every exit path - leaving it off would break MIME handling
		 * for the rest of the request - and Recycle releases the part and the header.
		 * Notice that the scope nests: DominoUtil.restoreState opens its own.
		 */
		try (MimeScope mime = MimeScope.conversionOff(session); Recycle parts = Recycle.of()) {
			MIMEEntity entity = parts.own(parent.getMIMEEntity(name));
			if (entity == null) {
				return null;
			}
			if (DominoUtil.isStoredObject(entity, parts)) {
				// A value this class wrote, in one of the formats it knows how to read
				return DominoUtil.restoreState(parent, name);
			}
			return null;
		} catch (Exception e) {
			LogUtil.trace(e);
			return null;
		}
	}

	public String getUnid() {
		return unid;
	}

	@SuppressWarnings("unchecked")
	public void load(final String unid) {
		setUnid(unid);
		if (StringUtil.isEmpty(getUnid())) {
			return;
		}
		try (Recycle parts = Recycle.of()) {
			Document doc = parts.own(ExtLibUtil.getCurrentDatabase().getDocumentByUNID(getUnid()));
			if (doc == null) {
				throw new IllegalArgumentException("No document exists with UNID " + getUnid());
			}
			DominoDocument wrappedDoc = DominoDocument.wrap(doc.getParentDatabase().getFilePath(), // databaseName
					doc, // Document
					null, // computeWithForm
					null, // concurrencyMode
					false, // allowDeletedDocs
					null, // saveLinksAs
					null // webQuerySaveAgent
					);
			/*
			 * The items are recycled as a whole and owned by the scope: every Item of the
			 * document goes with it, which also covers the ones skipped by the continues
			 * below. The MIME parts of the individual items join the same scope, so a
			 * failed item does not leave a handle behind.
			 */
			Vector items = doc.getItems();
			parts.add(items);
			for (Object eachItem : items) {
				if (!(eachItem instanceof Item)) {
					continue;
				}
				Item item = (Item) eachItem;
				String itemName = item.getName();
				if (IGNORED_ITEMS.contains(itemName)) {
					continue;
				}
				Object value = null;
				if (parts.own(doc.getMIMEEntity(itemName)) != null) {
					value = getItemValueMIME(doc, itemName);
				}
				if (value == null) {
					/*
					 * Either it is not a MIME item at all, or it is a MIME part that is not
					 * a stored object (a body or an attachment). Fall back to the wrapped
					 * document so a readable item is never replaced by null, which would be
					 * written back as an empty value on save.
					 */
					value = wrappedDoc.getValue(itemName);
				}
				if (value != null) {
					setValue(itemName, value);
				}
			}
		} catch (Exception e) {
			LogUtil.trace(e);
		}
	}

	protected boolean postSave() {
		return true;
	}

	protected boolean querySave() {
		return true;
	}

	/*
	 * Borrowed heavily from
	 * https://github.com/OpenNTF/org.openntf.domino/blob/master/org.openntf.domino/src/org/openntf/domino/impl/Document.java
	 */
	@SuppressWarnings("unchecked")
	protected Item replaceItemValue(final Document parent, final String itemName, final Object value) {
		Item result = null;
		boolean isNonSummary = false;
		try {
			if (value == null) {
				/*
				 * Null means "no value". Storing an empty string instead would make it
				 * indistinguishable from a deliberately blank field, so the item is removed:
				 * this class is a view of the document, not a shadow copy of it.
				 */
				Item existing = parent.getFirstItem(itemName);
				if (existing != null) {
					existing.remove();
				}
				return null;
			}
			String noteid_ = parent.getNoteID();
			if (value instanceof Collection || value instanceof Object[]) {
				if (value instanceof Collection) {
					if (((Collection) value).size() > MAX_NATIVE_VECTOR_SIZE) {
						throw new IllegalArgumentException();
					}
				} else if (value instanceof Object[]) {
					if (((Object[]) value).length > MAX_NATIVE_VECTOR_SIZE) {
						throw new IllegalArgumentException();
					}
				}
				Vector<Object> resultList = new Vector<>();
				Class<?> objectClass = null;
				long totalStringSize = 0;
				Collection<?> listValue = value instanceof Collection ? (Collection<?>) value : Arrays.asList((Object[]) value);
				for (Object valNode : listValue) {
					Object domNode = DominoUtil.toDominoFriendly(valNode);
					if (domNode != null) {
						if (objectClass == null) {
							objectClass = domNode.getClass();
						} else {
							if (!objectClass.equals(domNode.getClass())) {
								throw new IllegalArgumentException();
							}
						}
						if (domNode instanceof String) {
							totalStringSize += ((String) domNode).length();
							if (totalStringSize > 14000) {
								isNonSummary = true;
							}
							// Escape to serializing if there's too much text data
							// Leave fudge room for multibyte? This is clearly not the best way to do it
							if (totalStringSize > 60000) {
								throw new IllegalArgumentException();
							}
						}
						resultList.add(domNode);
					}
				}
				// If it ended up being something we could store, make note of the original class instead of the list class
				if (!listValue.isEmpty()) {
					removeMimePart(parent, itemName);
				} else {
					throw new IllegalArgumentException();
				}
				result = parent.replaceItemValue(itemName, resultList);
				if (isNonSummary) {
					result.setSummary(false);
				}
				DominoUtil.incinerate(resultList);
			} else {
				Object domNode = DominoUtil.toDominoFriendly(value);
				if (domNode instanceof String && ((String) domNode).length() > 60000) {
					throw new IllegalArgumentException();
				}
				try {
					removeMimePart(parent, itemName);
					result = parent.replaceItemValue(itemName, domNode);
					if (isNonSummary) {
						result.setSummary(false);
					}
				} catch (NotesException nativeError) {
					LogUtil.debug("Native error occured when replacing " + itemName + " item on doc " + getUnid() + " with a value of type "
							+ (domNode == null ? "null" : domNode.getClass().getName()) + " of value " + String.valueOf(domNode));
				}
				DominoUtil.incinerate(domNode);
			}
		} catch (IllegalArgumentException iae) {
			/*
			 * The value cannot be a native item. Encoding it is the normal answer now; the
			 * Java serialization chain below stays so that documents written by the previous
			 * version remain readable, and can go once none of them are left.
			 */
			ValueCodec codec = ValueCodecs.forValue(value);
			if (codec != null) {
				try {
					DominoUtil.saveEncoded(parent, itemName, ValueCodecs.encode(value));
					return null;
				} catch (Exception encodeError) {
					LogUtil.debug("Could not store the " + itemName + " item of doc " + getUnid() + " as " + codec.scheme()
							+ "; falling back to Java serialization");
					LogUtil.trace(encodeError);
				}
			}
			// Then try serialization
			try {
				if (value instanceof Serializable) {
					DominoUtil.saveState((Serializable) value, parent, itemName);
					// result = null;
				} else if (value instanceof DocumentCollection) {
					// NoteIDs would be faster for this and, particularly,
					// NoteCollection, but it should be replica-friendly
					DocumentCollection docs = (DocumentCollection) value;
					String[] unids = new String[docs.getCount()];
					int index = 0;
					Document doc = docs.getFirstDocument();
					while (doc != null) {
						unids[index++] = doc.getUniversalID();
						doc = DominoUtil.next(doc, docs);
					}
					Map<String, String> headers = new HashMap<>(1);
					headers.put("X-Original-Java-Class", "org.openntf.domino.DocumentCollection");
					DominoUtil.saveState(unids, parent, itemName, true, headers);
				} else if (value instanceof NoteCollection) {
					// Maybe it'd be faster to use .getNoteIDs - I'm not sure how
					// the performance compares
					NoteCollection notes = (NoteCollection) value;
					String[] unids = new String[notes.getCount()];
					String noteid = notes.getFirstNoteID();
					int index = 0;
					while (noteid != null && !noteid.isEmpty()) {
						unids[index++] = notes.getUNID(noteid);
						noteid = notes.getNextNoteID(noteid);
					}
					Map<String, String> headers = new HashMap<>(1);
					headers.put("X-Original-Java-Class", value.getClass().getCanonicalName());
					DominoUtil.saveState(unids, parent, itemName, true, headers);
				} else {
					// Check to see if it's a StateHolder
					try {
						Class<?> stateHolderClass = Class.forName("javax.faces.component.StateHolder");
						if (stateHolderClass.isInstance(value)) {
							Class<?> facesContextClass = Class.forName("javax.faces.context.FacesContext");
							Method getCurrentInstance = facesContextClass.getMethod("getCurrentInstance");
							Method saveState = stateHolderClass.getMethod("saveState", facesContextClass);
							Serializable state = (Serializable) saveState.invoke(value, getCurrentInstance.invoke(null));
							Map<String, String> headers = new HashMap<>();
							headers.put("X-Storage-Scheme", "StateHolder");
							headers.put("X-Original-Java-Class", value.getClass().getName());
							DominoUtil.saveState(state, parent, itemName, true, headers);
							result = null;
						} else {
							// Well, we tried.
							throw iae;
						}
					} catch (ClassNotFoundException cnfe) {
						throw iae;
					} catch (Throwable t) {
						LogUtil.trace(t);
					}
				}
			} catch (Throwable t) {
				LogUtil.trace(t);
			}
		} catch (Throwable t) {
			LogUtil.trace(t);
		}
		return result;
	}

	/**
	 * Drops an existing MIME part for an item before it is replaced by a native item,
	 * so that the document cannot end up holding both a native item and a MIME part of
	 * the same name.
	 *
	 * <p>
	 * {@code Document.removeItem} does the work: {@code MIMEEntity} has no {@code remove()},
	 * which is why the previous version of this code could not compile.
	 * </p>
	 */
	private static void removeMimePart(final Document parent, final String itemName) {
		try {
			parent.removeItem(itemName);
		} catch (NotesException e) {
			LogUtil.trace(e);
		}
	}

	public boolean save() {
		boolean result = false;
		if (!querySave()) {
			return false;
		}
		Document doc = null;
		try {
			/*
			 * The UNID is only recorded once the document really exists, so that a failed
			 * save of a new document does not leave the model pointing at a document that
			 * was never written.
			 */
			boolean isNew = StringUtil.isEmpty(getUnid());
			if (isNew) {
				doc = ExtLibUtil.getCurrentDatabase().createDocument();
				doc.replaceItemValue("Form", getFormName());
			} else {
				doc = ExtLibUtil.getCurrentDatabase().getDocumentByUNID(getUnid());
				if (doc == null) {
					throw new IllegalArgumentException("No document exists with UNID " + getUnid());
				}
			}
			for (Entry<Object, Object> entry : getValues().entrySet()) {
				String itemName = entry.getKey().toString();
				Item item = replaceItemValue(doc, itemName, entry.getValue());
				DominoUtil.incinerate(item);
			}
			if (doc.save()) {
				if (isNew) {
					setUnid(doc.getUniversalID());
				}
				result = postSave();
				if (!result) {
					addErrorMessage("The document was saved, but the post-save step failed.");
				}
			} else {
				addErrorMessage("The document could not be saved.");
			}
		} catch (Exception e) {
			LogUtil.trace(e);
			addErrorMessage("The document could not be saved: " + e.getMessage());
		} finally {
			DominoUtil.incinerate(doc);
		}
		return result;
	}

	/**
	 * Reports a problem to the user. The pages render these through an
	 * <code>xp:messages</code> control, so a failed save is no longer silent.
	 */
	protected static void addErrorMessage(final String message) {
		FacesContext context = FacesContext.getCurrentInstance();
		if (context != null) {
			context.addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, message, message));
		} else {
			LogUtil.debug(message);
		}
	}

	public void setUnid(final String unid) {
		this.unid = unid;
	}
}
