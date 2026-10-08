package vault;

import vault.exceptions.InvalidVaultException;


import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

import static vault.constants.Constants.*;

public class Vault {
    private final Path filePath;
    private VaultIndex vaultIndex;
    private long indexAddress;


    public Vault(String path) throws IOException, InvalidVaultException {
        Path filePath = Paths.get(path);
        int headerLength = 10; // Long.BYTES + HEADER_DELIMITER.length(); // 8 is the number of bytes of long (where index address is stored)
        if(Files.exists(filePath)){
            this.filePath = filePath;

            try(FileChannel ch = FileChannel.open(filePath)){
                // Header validation
                ByteBuffer buffer = ByteBuffer.allocate(headerLength);
                ch.read(buffer);
                buffer.flip();
                this.indexAddress = buffer.getLong();
                byte[] delimiterBytes = new byte[HEADER_DELIMITER.length()];
                buffer.get(Long.BYTES ,delimiterBytes);
                checkInvalidVault("header",delimiterBytes,"invalid headers");

                // index validation
                ByteBuffer indexBuffer = ByteBuffer.allocate(INDEX_DELIMITER.length());
                ch.position(this.indexAddress);
                ch.read(indexBuffer);
                byte[] indexDelimiterBytes = new byte[INDEX_DELIMITER.length()];
                indexBuffer.flip();
                indexBuffer.get(indexDelimiterBytes);
                checkInvalidVault("index",indexDelimiterBytes,"invalid index");



            }
            catch (BufferUnderflowException e){
                throw new InvalidVaultException("invalid headers or index");
            }
            catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        else {
            this.filePath =  Files.createFile(filePath);
            this.indexAddress = headerLength;
            System.out.println("file create : " + this.filePath.toAbsolutePath());
            ByteBuffer headerBuffer = ByteBuffer.allocate(headerLength);
            headerBuffer.putLong(headerLength);
            byte[] delimiter = HEADER_DELIMITER.getBytes(StandardCharsets.UTF_8);
            headerBuffer.put(delimiter);
            try(FileChannel ch = FileChannel.open(this.filePath, StandardOpenOption.READ,StandardOpenOption.WRITE)){
                headerBuffer.flip();
                ch.write(headerBuffer);
                ByteBuffer buffer = ByteBuffer.allocate(INDEX_DELIMITER.getBytes(StandardCharsets.UTF_8).length);
                buffer.put(INDEX_DELIMITER.getBytes(StandardCharsets.UTF_8));
                buffer.flip();
                ch.write(buffer);

                // Add the index length to the vault
                ByteBuffer indexLengthBuffer = ByteBuffer.allocate(Integer.BYTES);
                indexLengthBuffer.putInt(0);
                indexLengthBuffer.flip();
                ch.write(indexLengthBuffer);

                // write index end delimiter
                byte[] delimiterEndArray = INDEX_END_DELIMITER.getBytes(StandardCharsets.UTF_8);
                ByteBuffer byteBufferEnd = ByteBuffer.allocate(delimiterEndArray.length);
                byteBufferEnd.put(delimiterEndArray);
                byteBufferEnd.flip();
                ch.write(byteBufferEnd);

            }

        }

        vaultIndex = new VaultIndex(this.filePath);


    }

    public void addFile(String path) throws IOException {

        Path sourceFilePath = Paths.get(path);
        if( ! Files.exists( sourceFilePath ) ) {
            throw new FileNotFoundException(path);
        }
        if(! Files.isRegularFile(sourceFilePath)){
            throw new IOException("Not a file.");
        }

        long[] index = this.vaultIndex.readIndex(this.indexAddress);
        long[] newIndex = new long[index.length +1];
        System.arraycopy(index,0,newIndex,0,index.length);

        try(FileChannel chSrc = FileChannel.open(sourceFilePath, StandardOpenOption.READ);
            FileChannel chDst = FileChannel.open(this.filePath, StandardOpenOption.WRITE, StandardOpenOption.READ)){

            chDst.position(this.indexAddress  ); // move to index position in file
            newIndex[index.length ] = this.indexAddress; // add new file entry in the new index array
            chSrc.transferTo(0,chSrc.size(),chDst); // copy the source file data to vault's index postion

            // put the current file pointer as new index address and add index delimiter to the vault
            ByteBuffer buffer = ByteBuffer.allocate(INDEX_DELIMITER.getBytes(StandardCharsets.UTF_8).length);
            buffer.put(INDEX_DELIMITER.getBytes(StandardCharsets.UTF_8));
            buffer.flip();
            long newIndexAddress = chDst.position();
            chDst.write(buffer);

            // Add the index length to the vault
            ByteBuffer indexLengthBuffer = ByteBuffer.allocate(Integer.BYTES);
            indexLengthBuffer.putInt(newIndex.length);
            indexLengthBuffer.flip();
            chDst.write(indexLengthBuffer);

            // write the index data to the vault
            ByteBuffer indexBuffer = ByteBuffer.allocate(newIndex.length * Long.BYTES);
            indexBuffer.asLongBuffer().put(newIndex);
            chDst.write(indexBuffer);

            // write index end delimiter
            byte[] delimiterEndArray = INDEX_END_DELIMITER.getBytes(StandardCharsets.UTF_8);
            ByteBuffer byteBufferEnd = ByteBuffer.allocate(delimiterEndArray.length);
            byteBufferEnd.put(delimiterEndArray);
            byteBufferEnd.flip();
            chDst.write(byteBufferEnd);

            // Update the new index address at the beginning of the vault
            chDst.position(0);
            ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES);
            headerBuffer.putLong(newIndexAddress);
            headerBuffer.flip();
            chDst.write( headerBuffer );

        }

        System.out.println("File added succesfully to the vault: " + sourceFilePath.getFileName()  );



    }



    /*    Helper Functions   */

    private void checkInvalidVault(String type, byte[] delimiterBytes,String errorMessage) throws InvalidVaultException {
        String delimiterString = (type.equals("header")? HEADER_DELIMITER : INDEX_DELIMITER);
        String delimiter = new String(delimiterBytes, StandardCharsets.UTF_8);
        if( !delimiter.equals(delimiterString)){
            throw new InvalidVaultException(errorMessage);
        }
        else {
            System.out.println("Valid headers:" + delimiter);
        }
    }


}
