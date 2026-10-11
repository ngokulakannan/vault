package vault;

import vault.exceptions.InvalidVaultException;


import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;

import static vault.constants.Constants.*;

public class Vault {
    private final Path filePath;
    private VaultIndex vaultIndex;
    private long indexAddress;
    public enum NumberType {
         INT, LONG
    }


    public Vault(String path) throws IOException, InvalidVaultException {
        Path filePath = Paths.get(path);
        int headerLength = Long.BYTES + HEADER_DELIMITER.length(); // 8 is the number of bytes of long (where index address is stored)
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
                putNextStringIntoFile(ch.position(),INDEX_END_DELIMITER,false,ch);

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
        String fileName = sourceFilePath.getFileName().toString();

        long[] index = this.vaultIndex.readIndex(this.indexAddress);
        long[] newIndex = new long[index.length +1];
        System.arraycopy(index,0,newIndex,0,index.length);

        try(FileChannel chSrc = FileChannel.open(sourceFilePath, StandardOpenOption.READ);
            FileChannel chDst = FileChannel.open(this.filePath, StandardOpenOption.WRITE, StandardOpenOption.READ)){


            newIndex[index.length ] = this.indexAddress; // add new file entry in the new index array

            // Add filename at the old index position
            putNextStringIntoFile(this.indexAddress,fileName,true,chDst);
            putNumberIntoFile(chDst.position(),chSrc.size(),NumberType.INT,chDst); // Add file size
            chSrc.transferTo(0,chSrc.size(),chDst); // copy the source file data to vault's index position

            // put the current file pointer as new index address
            long newIndexAddress = chDst.position();

            //  add index delimiter to the vault
            putNextStringIntoFile(chDst.position(), INDEX_DELIMITER,false,chDst);

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
            putNextStringIntoFile(chDst.position(),INDEX_END_DELIMITER,false,chDst);


            // Update the new index address at the beginning of the vault
            chDst.position(0);
            ByteBuffer headerBuffer = ByteBuffer.allocate(Long.BYTES);
            headerBuffer.putLong(newIndexAddress);
            headerBuffer.flip();
            chDst.write( headerBuffer );

            // update the indexAddress to this object
            this.indexAddress = newIndexAddress;

        }

        System.out.println("File added succesfully to the vault: " + fileName  );

    }

    public void extractFile(int indexOffset) throws IOException {
        long[] index = this.vaultIndex.readIndex(this.indexAddress);
        long fileAddress = index[indexOffset];
        try(FileChannel chSrc = FileChannel.open(this.filePath, StandardOpenOption.READ)){
            String fileName = getStringFromFile(fileAddress,chSrc);
            Path newFilePath =   Path.of( this.filePath.toAbsolutePath().getParent().toString() , fileName )  ;
            try(FileChannel chDst = FileChannel.open(newFilePath , StandardOpenOption.WRITE,StandardOpenOption.CREATE)) {
                if(chDst.size() >0 ){
                    chDst.truncate(chDst.size());
                    System.out.println("Truncate");
                }
                int size = getNumberFromIntoFile(chSrc.position(), NumberType.INT, chSrc ).intValue();
                chSrc.transferTo(chSrc.position(),size,chDst);
            }
        }
    }


    public String[] getFiles() throws IOException {

        long[] index = this.vaultIndex.readIndex(this.indexAddress);
        String[] files = new String[index.length];

        try (FileChannel ch = FileChannel.open(this.filePath, StandardOpenOption.READ)){
            for(int i = 0; i< index.length; i++){
                files[i] = getStringFromFile(index[i],ch);
            }
        }

        return files;
    }

    public Map<Long,String> getFilesWithMetadata() throws IOException {
        return this.vaultIndex.readIndexWithMetadata(this.indexAddress);
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

    private String getStringFromFile(long position) throws IOException{
        try (FileChannel ch = FileChannel.open(this.filePath, StandardOpenOption.READ)) {
            return getStringFromFile(position,ch);
        }
    }

    public static String getStringFromFile(long position, FileChannel ch) throws IOException{
        ch.position(position);
        ByteBuffer lengthBuffer = ByteBuffer.allocate(Integer.BYTES);
        ch.read(lengthBuffer);
        lengthBuffer.flip();
        int length = lengthBuffer.getInt();
        ByteBuffer nextStringBuffer = ByteBuffer.allocate( length);
        ch.read(nextStringBuffer);
        nextStringBuffer.flip();
        byte[] nextStringAsBytes = new byte[length];
        nextStringBuffer.get(nextStringAsBytes);
        return new String(nextStringAsBytes, StandardCharsets.UTF_8);
    }

    private int putNextStringIntoFile(long position, String word, boolean storeLength, FileChannel ch) throws IOException {

        byte[] wordByteArray = word.getBytes(StandardCharsets.UTF_8);
        int length = storeLength ? (Integer.BYTES + wordByteArray.length) : wordByteArray.length;
        ByteBuffer byteBufferEnd = ByteBuffer.allocate(length);
        if(storeLength){
            byteBufferEnd.putInt(wordByteArray.length);
        }
        byteBufferEnd.put(wordByteArray);
        byteBufferEnd.flip();
        ch.position(position);
        return ch.write(byteBufferEnd);
    }

    private int putNextStringIntoFile(long position, String word, boolean storeLength) throws IOException{
        try (FileChannel ch = FileChannel.open(this.filePath, StandardOpenOption.READ)) {
            return putNextStringIntoFile(position,word,storeLength,ch);
        }
    }

    private int putNumberIntoFile(long position,Number number, NumberType type,FileChannel ch) throws IOException{
        boolean isInt = type == NumberType.INT;
        int size = isInt? Integer.BYTES: Long.BYTES;
        ByteBuffer byteBuffer = ByteBuffer.allocate(size);
        ch.position(position);
        if(isInt){
            byteBuffer.putInt(number.intValue());
        }
        else {
            byteBuffer.putLong(number.longValue());
        }
        byteBuffer.flip();
        return ch.write(byteBuffer);

    }

    private Number getNumberFromIntoFile(long position,  NumberType type, FileChannel ch) throws IOException{
        ch.position( position );

        boolean isInt = type == NumberType.INT;
        int size = isInt? Integer.BYTES: Long.BYTES;
        ByteBuffer indexLengthBuffer = ByteBuffer.allocate(size);
        ch.read(indexLengthBuffer);
        indexLengthBuffer.flip();
        if(isInt){
            return indexLengthBuffer.getInt();
        }
        else {
            return indexLengthBuffer.getLong();
        }
    }


}
