package org.openntf.howyabean.model;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.faces.context.FacesContext;

import org.openntf.xsp.model.AbstractMapModel;
import org.openntf.xsp.util.codec.Structured;

import com.ibm.xsp.extlib.util.ExtLibUtil;
import com.ibm.xsp.model.DataObject;

/**
 * One phone number of a contact: a map-backed bean, so the fields are whatever the page
 * binds to ({@code category}, {@code digits}, ...).
 * 
 * <p>
 * It implements {@link Structured} so that a phone book can be stored by a codec instead of
 * being Java-serialized: the map is written as data, and {@link #fromMap(Map)} is the only
 * way a stored phone number ever comes back. The map keys are the field names on the wire,
 * so renaming a key here is a data migration.
 * </p>
 */
public class PhoneNumber extends AbstractMapModel implements DataObject, Structured {
	/** The name this type is stored under; it is data in the document, not a class load. */
	public static final String TYPE_NAME = "org.openntf.howyabean.model.PhoneNumber";

	private static final long serialVersionUID = 1L;
	private transient ContactModel contact;

	public ContactModel getContact() {
		if (contact == null) {
			contact = (ContactModel) ExtLibUtil.resolveVariable(FacesContext.getCurrentInstance(), "contact");
		}
		return contact;
	}

	@Override
	public boolean isReadOnly(final Object key) {
		return !getContact().isEditMode();
	}

	@Override
	public String type() {
		return TYPE_NAME;
	}

	@Override
	public Map<String, Object> toMap() {
		Map<String, Object> result = new LinkedHashMap<>();
		for (Map.Entry<Object, Object> entry : getValues().entrySet()) {
			result.put(String.valueOf(entry.getKey()), entry.getValue());
		}
		return result;
	}

	/** Rebuilds a stored phone number. Registered as the factory of {@link #TYPE_NAME}. */
	public static PhoneNumber fromMap(final Map<String, Object> values) {
		PhoneNumber result = new PhoneNumber();
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			result.getValues().put(entry.getKey(), entry.getValue());
		}
		return result;
	}
}
