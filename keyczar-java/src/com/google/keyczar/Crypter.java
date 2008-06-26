/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.keyczar;

import com.google.keyczar.enums.KeyPurpose;
import com.google.keyczar.exceptions.BadVersionException;
import com.google.keyczar.exceptions.InvalidSignatureException;
import com.google.keyczar.exceptions.KeyNotFoundException;
import com.google.keyczar.exceptions.KeyczarException;
import com.google.keyczar.exceptions.ShortCiphertextException;
import com.google.keyczar.interfaces.DecryptingStream;
import com.google.keyczar.interfaces.KeyczarReader;
import com.google.keyczar.interfaces.VerifyingStream;
import com.google.keyczar.util.Base64Coder;

import org.apache.log4j.Logger;

import java.nio.ByteBuffer;

/**
 * Crypters may both encrypt and decrypt data using sets of symmetric or private
 * keys. Sets of public keys may only be used with {@link Encrypter} objects.
 * 
 * @author steveweis@gmail.com (Steve Weis)
 */
public class Crypter extends Encrypter {
  private static final int DECRYPT_CHUNK_SIZE = 1024;
  private static final Logger logger = Logger.getLogger(Crypter.class);
  private static final StreamCache<DecryptingStream> CRYPT_CACHE
    = new StreamCache<DecryptingStream>();
  
  /**
   * Initialize a new Crypter with a KeyczarReader. The corresponding key set
   * must have a purpose {@link com.google.keyczar.enums.KeyPurpose#DECRYPT_AND_ENCRYPT}.
   * 
   * @param reader A reader to read keys from
   * @throws KeyczarException In the event of an IO error reading keys or if the
   * key set does not have the appropriate purpose.
   */
  public Crypter(KeyczarReader reader) throws KeyczarException {
    super(reader);
  }

  /**
   * Initialize a new Crypter with a key set location. This will attempt to
   * read the keys using a KeyczarFileReader. The corresponding key set
   * must have a purpose of
   * {@link com.google.keyczar.enums.KeyPurpose#DECRYPT_AND_ENCRYPT}.
   *  
   * @param fileLocation Directory containing a key set
   * @throws KeyczarException In the event of an IO error reading keys or if the
   * key set does not have the appropriate purpose.
   */
  public Crypter(String fileLocation) throws KeyczarException {
    super(fileLocation);
  }

  /**
   * Decrypt the given byte array of ciphertext
   * 
   * @param input The input ciphertext
   * @return The decrypted plaintext
   * @throws KeyczarException If the input is malformed, the ciphertext
   * signature does not verify, the decryption key is not found, or a JCE
   * error occurs.
   */
  public byte[] decrypt(byte[] input) throws KeyczarException {
    ByteBuffer output = ByteBuffer.allocate(input.length);
    decrypt(ByteBuffer.wrap(input), output);
    output.reset();
    byte[] outputBytes = new byte[output.remaining()];
    output.get(outputBytes);
    return outputBytes;
  }

  /**
   * Decrypt the given ciphertext input ByteBuffer and write the decrypted
   * plaintext to the output ByteBuffer 
   *  
   * @param input The input ciphertext. Will not be modified.
   * @param output The output buffer to write the decrypted plaintext
   * @throws KeyczarException If the input is malformed, the ciphertext
   * signature does not verify, the decryption key is not found, or a JCE
   * error occurs.
   */
  public void decrypt(ByteBuffer input, ByteBuffer output)
      throws KeyczarException {
    ByteBuffer inputCopy = input.asReadOnlyBuffer();
    logger.info("Decrypting " + inputCopy.remaining() + " bytes");
    if (inputCopy.remaining() < HEADER_SIZE) {
      throw new ShortCiphertextException(inputCopy.remaining());
    }
    byte version = inputCopy.get();
    if (version != VERSION) {
      throw new BadVersionException(version);
    }

    byte[] hash = new byte[KEY_HASH_SIZE];
    inputCopy.get(hash);
    KeyczarKey key = getKey(hash);
    if (key == null) {
      throw new KeyNotFoundException(hash);
    }
    
    // The input to decrypt is now positioned at the start of the ciphertext
    inputCopy.mark();
    
    DecryptingStream cryptStream = CRYPT_CACHE.get(key); 
    if (cryptStream == null) {
      cryptStream = (DecryptingStream) key.getStream();
    }
    
    VerifyingStream verifyStream = cryptStream.getVerifyingStream();    
    if (inputCopy.remaining() < verifyStream.digestSize()) {
      throw new ShortCiphertextException(inputCopy.remaining());
    }
    
    // Slice off the signature into another buffer
    inputCopy.position(inputCopy.limit() - verifyStream.digestSize());
    ByteBuffer signature = inputCopy.slice();
    
    // Reset the position of the input to start of the ciphertext
    inputCopy.reset();
    inputCopy.limit(inputCopy.limit() - verifyStream.digestSize());
    
    // Initialize the crypt stream. This may read an IV if any.
    cryptStream.initDecrypt(inputCopy);
    
    // Verify the header and IV if any
    ByteBuffer headerAndIvToVerify = input.asReadOnlyBuffer();
    headerAndIvToVerify.limit(inputCopy.position());
    verifyStream.initVerify();
    verifyStream.updateVerify(headerAndIvToVerify);
    
    output.mark();
    // This will process large input in chunks, rather than all at once. This
    // avoids making two passes through memory. 
    while (inputCopy.remaining() > DECRYPT_CHUNK_SIZE) {
      ByteBuffer ciphertextChunk = inputCopy.slice();
      ciphertextChunk.limit(DECRYPT_CHUNK_SIZE);
      cryptStream.updateDecrypt(ciphertextChunk, output);
      ciphertextChunk.rewind();
      verifyStream.updateVerify(ciphertextChunk);
      inputCopy.position(inputCopy.position() + DECRYPT_CHUNK_SIZE);
    }
    inputCopy.mark();
    verifyStream.updateVerify(inputCopy);
    if (!verifyStream.verify(signature)) {
      throw new InvalidSignatureException();
    }
    inputCopy.reset();
    cryptStream.doFinalDecrypt(inputCopy, output);
    output.limit(output.position());
    CRYPT_CACHE.put(key, cryptStream);
  }

  /**
   * Decrypt the given web-safe Base64 encoded ciphertext and return the
   * decrypted plaintext as a String.
   * 
   * @param ciphertext The encrypted ciphertext in web-safe Base64 format
   * @return The decrypted plaintext as a string
   * @throws KeyczarException If the input is malformed, the ciphertext
   * signature does not verify, the decryption key is not found, the input is
   * not web-safe Base64 encoded, or a JCE error occurs.
   */
  public String decrypt(String ciphertext) throws KeyczarException {
    return new String(decrypt(Base64Coder.decode(ciphertext)));
  }

  /*
   * (non-Javadoc)
   * 
   * @see keyczar.Keyczar#isAcceptablePurpose(keyczar.KeyPurpose)
   */
  @Override
  boolean isAcceptablePurpose(KeyPurpose purpose) {
    return purpose == KeyPurpose.DECRYPT_AND_ENCRYPT;
  }
}