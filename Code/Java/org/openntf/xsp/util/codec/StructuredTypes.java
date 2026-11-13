package org.openntf.xsp.util.codec;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * The set of {@link Structured} types a stored value may decode into.
 * 
 * <p>
 * This is the allow-list that makes the stored type name harmless: a payload can contain
 * any string it likes, but only a name that the running application registered in this
 * registry can be turned back into an object, and the factory that does it was chosen by
 * the application, not by the payload. An unknown name is a hard failure
 * ({@link IllegalArgumentException}), never a silent {@code Map}.
 * </p>
 * 
 * <p>
 * Registration belongs where a model declares its shape - the model class registers its
 * own types in a static initializer, so a design refresh that reloads the classes
 * registers the new factories and the old ones disappear with the old classloader.
 * </p>
 */
public final class StructuredTypes {
	private static final ConcurrentMap<String, Function<Map<String, Object>, ?>> TYPES = new ConcurrentHashMap<>();

	private StructuredTypes() {
	}

	/**
	 * Registers the factory for a type name. Registering the same name again replaces the
	 * factory, which is what a design refresh needs: the name stays, the class behind it is
	 * a new one.
	 */
	public static void register(final String type, final Function<Map<String, Object>, ?> factory) {
		if (type == null || type.isEmpty()) {
			throw new IllegalArgumentException("A structured type needs a name");
		}
		if (factory == null) {
			throw new IllegalArgumentException("The structured type " + type + " needs a factory");
		}
		TYPES.put(type, factory);
	}

	/**
	 * The factory for a type name, or an {@link IllegalArgumentException} naming the type
	 * if it is not registered.
	 */
	public static Function<Map<String, Object>, ?> factoryFor(final String type) {
		Function<Map<String, Object>, ?> result = TYPES.get(type);
		if (result == null) {
			throw new IllegalArgumentException("No structured type is registered as '" + type
					+ "', so the stored value cannot be rebuilt; register a factory for it");
		}
		return result;
	}

	public static boolean isRegistered(final String type) {
		return TYPES.containsKey(type);
	}
}
