package org.openntf.howyabean.model;

import java.util.ArrayList;
import java.util.List;

import javax.faces.context.FacesContext;

import lotus.domino.Document;

import org.openntf.xsp.model.AbstractSmartDocumentModel;
import org.openntf.xsp.util.LogUtil;
import org.openntf.xsp.util.Recycle;
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

	/**
	 * Deletes the document behind a list row and recycles the handle. The list pages call
	 * this from their delete action, so a failure becomes a message on the page instead of
	 * an unhandled SSJS exception and a leaked document handle.
	 *
	 * @param unid the UNID of the row, from the view entry
	 * @return whether the document was deleted
	 */
	public boolean deleteContact(final String unid) {
		if (unid == null || unid.isEmpty()) {
			addErrorMessage("The contact could not be deleted: the row carries no document id.");
			return false;
		}
		try (Recycle parts = Recycle.of()) {
			Document doc = parts.own(ExtLibUtil.getCurrentDatabase().getDocumentByUNID(unid));
			if (doc == null) {
				addErrorMessage("The contact could not be deleted: the document no longer exists.");
				return false;
			}
			doc.remove(true);
			return true;
		} catch (Exception e) {
			LogUtil.trace(e);
			addErrorMessage("The contact could not be deleted: " + e.getMessage());
			return false;
		}
	}

	public void setPhoneBook(final List<PhoneNumber> phoneBook) {
		this.phoneBook = phoneBook;
	}
}
