package org.openntf.howyabean.model;

import java.util.ArrayList;
import java.util.List;

import javax.faces.context.FacesContext;

import org.openntf.xsp.model.AbstractSmartDocumentModel;

import com.ibm.xsp.extlib.util.ExtLibUtil;

public class ContactModel extends AbstractSmartDocumentModel {
	private static final long serialVersionUID = 1L;
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
			phoneBook = new ArrayList<PhoneNumber>();
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
