package org.openntf.xsp.util;

import lotus.domino.NotesException;
import lotus.domino.Session;

/**
 * Turns MIME conversion off for the duration of a block and restores the previous setting
 * when the block ends, whatever it does.
 * <p>
 * Reading or writing a stored object means handling the raw MIME part, which only works
 * with conversion off - but leaving it off silently breaks MIME handling for everything
 * else that runs on the thread afterwards, and a bare {@code finally} is easy to forget on
 * one of the early return paths. As a resource it cannot be forgotten:
 * </p>
 * 
 * <pre>
 * try (MimeScope mime = MimeScope.off(session); Recycle parts = Recycle.of()) {
 * 	...
 * }
 * </pre>
 * 
 * <p>
 * Declared before any {@link Recycle} in the same statement, it is closed last: the parts
 * are recycled while conversion is still off, and only then is the session restored.
 * Scopes nest correctly, so a helper that opens its own scope inside another one restores
 * the outer state and not the caller's original one.
 * </p>
 */
public final class MimeScope implements AutoCloseable {
	private final Session session;
	private final boolean previous;

	private MimeScope(final Session session) throws NotesException {
		this.session = session;
		this.previous = session.isConvertMIME();
		session.setConvertMIME(false);
	}

	/** Turns conversion off and keeps the previous setting to restore on close. */
	public static MimeScope off(final Session session) throws NotesException {
		return new MimeScope(session);
	}

	/** Whether conversion was on before this scope started, i.e. what closing will restore. */
	public boolean wasOn() {
		return previous;
	}

	@Override
	public void close() {
		try {
			session.setConvertMIME(previous);
		} catch (NotesException e) {
			LogUtil.trace(e);
		}
	}
}
