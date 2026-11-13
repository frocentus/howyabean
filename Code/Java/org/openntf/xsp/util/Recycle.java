package org.openntf.xsp.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import lotus.domino.Base;
import lotus.domino.NotesException;

/**
 * A scope that owns Domino objects and recycles them when it is closed, so a block can
 * declare which handles it takes and never write a {@code finally} for them:
 * 
 * <pre>
 * try (MimeScope mime = MimeScope.conversionOff(session); Recycle parts = Recycle.of(entity)) {
 * 	Stream stream = parts.own(session.createStream());
 * 	...
 * }
 * </pre>
 * 
 * <p>
 * Every Domino object extends {@link Base}, so one class covers documents, items, MIME
 * parts, streams, views and collections. Objects are recycled in reverse acquisition order
 * (last in, first out), which matches the order try-with-resources closes independent
 * resources and releases children before the parents they came from.
 * </p>
 * 
 * <p>
 * Closing is idempotent, so an explicit recycle in the middle of the block can be followed
 * by the scope closing itself without a second {@code recycle()} call on a dead handle. The
 * scope is meant per method, not per value: it allocates one object per scope.
 * </p>
 */
public final class Recycle implements AutoCloseable {
	private final List<Base> owned = new ArrayList<>();
	private boolean closed;

	private Recycle() {
	}

	/**
	 * Opens a scope, optionally owning the given objects right away. With no arguments this
	 * is the form for objects that only become available inside the block.
	 */
	public static Recycle of(final Base... values) {
		return new Recycle().add(values);
	}

	/** Opens a scope owning every Domino object of the given collection, e.g. a document's items. */
	public static Recycle of(final Collection<?> values) {
		return new Recycle().add(values);
	}

	public Recycle add(final Base... values) {
		if (values != null) {
			for (Base each : values) {
				add(each);
			}
		}
		return this;
	}

	public Recycle add(final Collection<?> values) {
		if (values != null) {
			for (Object each : values) {
				if (each instanceof Base) {
					add((Base) each);
				}
			}
		}
		return this;
	}

	private void add(final Base value) {
		if (closed) {
			throw new IllegalStateException("Recycle scope is already closed");
		}
		if (value != null) {
			owned.add(value);
		}
	}

	/**
	 * Hands a handle to the scope and gives it straight back, so an acquisition and its
	 * ownership can be written as one expression:
	 * {@code Stream stream = parts.own(session.createStream());}
	 */
	public <T extends Base> T own(final T value) {
		add(value);
		return value;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		for (int i = owned.size() - 1; i >= 0; i--) {
			Base each = owned.set(i, null);
			if (each != null) {
				try {
					each.recycle();
				} catch (NotesException e) {
					/*
					 * Recycling is best effort: a failure here must not mask an exception
					 * that is already travelling out of the block.
					 */
					LogUtil.trace(e);
				}
			}
		}
		owned.clear();
	}
}
