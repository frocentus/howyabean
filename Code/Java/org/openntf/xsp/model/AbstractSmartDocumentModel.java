package org.openntf.xsp.model;

import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.faces.context.FacesContext;

import org.openntf.xsp.util.LogUtil;

import com.ibm.commons.util.StringUtil;
import com.ibm.xsp.extlib.util.ExtLibUtil;

/**
 * Adds bean-property handling on top of {@link AbstractDocumentMapModel}: a key that is
 * reachable through a public getter/setter is served by the bean, anything else by the
 * underlying value map.
 * <p>
 * Whether a key is a bean property is a property of the <em>class</em>, not of the
 * instance, so it is resolved once per class through {@link Introspector} and cached.
 * Reflection is therefore never used as control flow: a document item is a map miss, not a
 * throw, and the (very hot) page lifecycle pays one hash lookup per attribute instead of a
 * caught {@code NoSuchMethodException}.
 * </p>
 */
public abstract class AbstractSmartDocumentModel extends AbstractDocumentMapModel {
	private static final long serialVersionUID = 1L;

	/**
	 * The reflective accessors of one bean property. Either method can be {@code null}
	 * (write-only or read-only property); {@code type} is {@code null} when it could not
	 * be determined.
	 */
	private record Accessor(Method read, Method write, Class<?> type) {
	}

	/**
	 * Per-class cache of the resolved accessors. Deliberately static: the model itself is
	 * view scoped and therefore serialized by JSF, and neither {@link Method} nor
	 * {@link PropertyDescriptor} is serializable. Static state is released together with
	 * the XPages class loader when the design is refreshed.
	 */
	private static final ConcurrentMap<Class<?>, Map<String, Accessor>> ACCESSORS = new ConcurrentHashMap<>();

	private boolean editMode;

	public AbstractSmartDocumentModel() {
		super();
		String action = ExtLibUtil.readParameter(FacesContext.getCurrentInstance(), "action");
		if (StringUtil.isEmpty(getUnid()) || "edit".equalsIgnoreCase(action)) {
			edit();
		}
	}

	/**
	 * The bean property behind this key, or {@code null} when the key is a document item.
	 * This is the only place where the "bean or map" decision is made.
	 */
	private Accessor beanProperty(final Object key) {
		if (key == null) {
			return null;
		}
		return accessorsFor(getClass()).get(key.toString());
	}

	private static Map<String, Accessor> accessorsFor(final Class<?> clazz) {
		/*
		 * computeIfAbsent is atomic on its own: the map guarantees that the introspector
		 * runs once per class even when the very first page is hit concurrently.
		 */
		return ACCESSORS.computeIfAbsent(clazz, AbstractSmartDocumentModel::buildAccessors);
	}

	/**
	 * Unwraps reflection's {@link InvocationTargetException} so the trace shows the cause
	 * the bean itself threw instead of the reflection wrapper around it.
	 */
	private static Throwable unwrap(final Exception e) {
		if (e instanceof InvocationTargetException) {
			Throwable cause = ((InvocationTargetException) e).getTargetException();
			return cause == null ? e : cause;
		}
		return e;
	}

	private static Map<String, Accessor> buildAccessors(final Class<?> clazz) {
		Map<String, Accessor> result = new HashMap<>();
		try {
			for (PropertyDescriptor descriptor : Introspector.getBeanInfo(clazz).getPropertyDescriptors()) {
				/*
				 * getReadMethod and getWriteMethod stopped throwing IntrospectionException in Java
				 * 7; the only call here that still throws is getBeanInfo itself, so the accessors
				 * are read straight out of the descriptor.
				 */
				result.put(descriptor.getName(),
						new Accessor(descriptor.getReadMethod(), descriptor.getWriteMethod(), descriptor.getPropertyType()));
			}
		} catch (IntrospectionException e) {
			// No bean properties at all: every key then resolves through the document map
			LogUtil.trace(e);
		}
		return Map.copyOf(result);
	}

	public void edit() {
		setEditMode(true);
	}

	@Override
	public Class<?> getType(final Object key) {
		Accessor property = beanProperty(key);
		if (property == null || property.type() == null) {
			return super.getType(key);
		}
		return property.type();
	}

	protected <T> T getTypedValue(final String name, final Class<T> type) {
		return type.cast(getValues().get(name));
	}

	@Override
	public Object getValue(final Object key) {
		Accessor property = beanProperty(key);
		/*
		 * A write-only property cannot be read, so it is treated like a document item -
		 * which is exactly what the reflective lookup used to achieve by throwing.
		 */
		if (property == null || property.read() == null) {
			return super.getValue(key);
		}
		try {
			return property.read().invoke(this);
		} catch (IllegalAccessException | InvocationTargetException e) {
			/*
			 * The getter itself failed. Falling back to the map keeps the page alive, but
			 * trace the real cause rather than a reflection wrapper.
			 */
			LogUtil.trace(unwrap(e));
			return super.getValue(key);
		}
	}

	public boolean isEditMode() {
		return editMode;
	}

	@Override
	public boolean isReadOnly(final Object key) {
		if (!isEditMode()) {
			return true;
		}
		Accessor property = beanProperty(key);
		if (property == null) {
			return super.isReadOnly(key);
		}
		return property.write() == null;
	}

	public void setEditMode(final boolean editMode) {
		this.editMode = editMode;
	}

	@Override
	public void setValue(final Object key, final Object value) {
		Accessor property = beanProperty(key);
		if (property == null || property.write() == null) {
			super.setValue(key, value);
			return;
		}
		try {
			property.write().invoke(this, value);
		} catch (IllegalAccessException | InvocationTargetException e) {
			LogUtil.trace(unwrap(e));
			super.setValue(key, value);
		}
	}
}
