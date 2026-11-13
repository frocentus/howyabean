package org.openntf.howyabean.model;

import java.util.ArrayList;
import java.util.List;

import javax.faces.context.FacesContext;

import org.openntf.xsp.model.AbstractSmartDocumentModel;
import org.openntf.xsp.util.codec.StructuredTypes;

import com.ibm.xsp.extlib.util.ExtLibUtil;

public class ContactModel extends AbstractSmartDocumentModel {
	private static final long serialVersionUID = 1L;

	/*
	 * A stored value names its type, and this is where the name is resolved: decoding a
	 * contact needs the factory before the first PhoneNumber exists, so the registration
	 * belongs to the class that declares the shape rather than to the class that is stored.
	 * A design refresh reloads this class and registers the new factory; the old one goes
	 * with the old classloader.
	 */
	static {
		StructuredTypes.register(PhoneNumber.TYPE_NAME, PhoneNumber::fromMap);
	}
	private List<PhoneNumber> phoneBook;

	public void addPhoneNumber() {
		getPhoneBook().add(new PhoneNumber());
	}

	@Override
	protected String getFormName() {
		return "contact";
	}

	public List<PhoneNumber> getPhoneBook() {
		if (phoneBook == null) {
			phoneBook = new ArrayList<>();
		}
		return phoneBook;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void load(final String unid) {
		super.load(unid);
		if (getValues().containsKey("phoneBook")) {
			setPhoneBook((List<PhoneNumber>) getValues().get("phoneBook"));
		}
	}

	@Override
	protected boolean querySave() {
		getValues().put("phoneBook", getPhoneBook());
		return true;
	}

	public void removePhoneNumber() {
		getPhoneBook().remove(ExtLibUtil.resolveVariable(FacesContext.getCurrentInstance(), "eachPhone"));
	}

	public void setPhoneBook(final List<PhoneNumber> phoneBook) {
		this.phoneBook = phoneBook;
	}
}
