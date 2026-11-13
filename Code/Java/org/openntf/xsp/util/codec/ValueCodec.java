package org.openntf.xsp.util.codec;

import java.io.IOException;

/**
 * One way of turning an application value into bytes and back.
 * 
 * <p>
 * A codec is the seam that replaces the "try a native item, then serialize the Java
 * object" chain: the storage format is chosen by the codec registry, becomes part of the
 * document (the {@link #scheme()} is written as the {@code X-Storage-Scheme} header), and
 * is therefore readable again by the same codec without guessing.
 * </p>
 * 
 * <p>
 * The contract that matters for security: {@link #decode(byte[])} is given bytes and
 * returns values, and it never instantiates a class named by those bytes. A codec may
 * rebuild objects, but only through {@link StructuredTypes}, which is a registry the
 * application filled in.
 * </p>
 */
public interface ValueCodec {
	/**
	 * The storage scheme, written to the item as {@code X-Storage-Scheme}. It carries a
	 * version ({@code msgpack/1}) so that a format change is a new scheme rather than a
	 * change of meaning for existing documents.
	 */
	String scheme();

	/** The MIME content type the payload is stored under, e.g. {@code application/x-msgpack}. */
	String contentType();

	/**
	 * Whether this codec is willing to encode the value. Only a shallow test: the value is
	 * already known not to be storable as a native Domino item, and {@link #encode(Object)}
	 * validates the contents in depth.
	 */
	boolean canEncode(Object value);

	/**
	 * The payload for a value.
	 * 
	 * @throws IOException
	 *             if the value is too large to store
	 * @throws IllegalArgumentException
	 *             if the value contains something the format cannot represent
	 */
	byte[] encode(Object value) throws IOException;

	/**
	 * The value for a payload.
	 * 
	 * @throws IOException
	 *             if the payload is malformed, truncated, too large or not what the scheme
	 *             says it is
	 * @throws IllegalArgumentException
	 *             if the payload names a type that is not registered
	 */
	Object decode(byte[] payload) throws IOException;
}
