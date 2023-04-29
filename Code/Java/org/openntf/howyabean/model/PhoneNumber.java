package org.openntf.howyabean.model;

import javax.faces.context.FacesContext;

import org.openntf.xsp.model.AbstractMapModel;

import com.ibm.xsp.extlib.util.ExtLibUtil;
import com.ibm.xsp.model.DataObject;

public class PhoneNumber extends AbstractMapModel implements DataObject {
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
}
