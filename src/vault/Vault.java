package vault;

import vault.exceptions.InvalidVaultException;


import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static vault.constants.Constants.*;

public class Vault {
    private final Path filePath;
    private VaultIndex vaultIndex;
    private long indexAddress;
    public enum NumberType {
         INT, LONG
    }
    private record FreeSpaceInfo(long sizeTowrite, long resultOffset, long remainingSize, long remainingOffset){};


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

            try(FileChannel ch = FileChannel.open(this.filePath, StandardOpenOption.READ,StandardOpenOption.WRITE)){
                ByteBuffer headerBuffer = ByteBuffer.allocate(headerLength);
                headerBuffer.putLong(headerLength);
                byte[] delimiter = HEADER_DELIMITER.getBytes(StandardCharsets.UTF_8);
                headerBuffer.put(delimiter);
                headerBuffer.flip();
                ch.write(headerBuffer);

                // write index delimiter
                putNextStringIntoFile(ch.position(),INDEX_DELIMITER,false,ch);

                // Add the index length to the vault
                putNumberIntoFile(ch.position(),0,NumberType.INT,ch);

                putNextStringIntoFile(ch.position(),INDEX_FREE_SPACE_DELIMITER,false,ch);
                putNumberIntoFile(ch.position(),0,NumberType.INT,ch);

                // write index end delimiter
                putNextStringIntoFile(ch.position(),INDEX_END_DELIMITER,false,ch);

            }

        }

        vaultIndex = new VaultIndex(this.filePath,this.indexAddress);


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
        long fileSize = Files.size(sourceFilePath);
        long modifiedTime =  Files.getLastModifiedTime(sourceFilePath).toMillis();
        boolean isCompressed = false;


        Map<String, VaultIndex.VaultIndexItem> index = this.vaultIndex.getIndex();
        Map<String, List<String>> hashIndex = this.vaultIndex.getHashIndex();
        TreeMap<Long,List<Long>> freeIndex = this.vaultIndex.getFreeIndex();
        boolean removeHashIndex = index.containsKey(fileName); // because adding same filename will make the old file(with same hash) as ghost one, so it has to remove from hash index


        try(FileChannel chSrc = FileChannel.open(sourceFilePath, StandardOpenOption.READ);
            FileChannel chDst = FileChannel.open(this.filePath, StandardOpenOption.WRITE, StandardOpenOption.READ)){

            long fileOffset = this.indexAddress;
            FreeSpaceInfo freeSpaceInfo = getRemainingFreeSpaceAndOffsetInfo(fileSize,fileName);
            System.out.println("free space ued info: "+ freeSpaceInfo);
            long freeSpaceOffset = freeSpaceInfo.resultOffset;
            if(freeSpaceOffset > 0){
                fileOffset = freeSpaceOffset;
            }


            putNextStringIntoFile(fileOffset,fileName,true,chDst); // Add filename at the old index position
            putNumberIntoFile(chDst.position(),chSrc.size(),NumberType.LONG,chDst); // Add file size

            MessageDigest msgdigest = MessageDigest.getInstance("SHA-256");

            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024); // 64kb
            while (chSrc.read(buffer) > -1){
                buffer.flip();
                msgdigest.update(buffer);
                buffer.rewind();
                while (buffer.hasRemaining()) {
                    chDst.write(buffer);
                }
                buffer.clear();
            }

            byte[] bytes = msgdigest.digest();
            String sha256 = HexFormat.of().formatHex(bytes);

            if(hashIndex.containsKey(sha256)){ // same hash
                if(removeHashIndex) { // same hash and same file name
                    if(freeSpaceOffset==0){ // if not used free space , truncate it
                        chDst.truncate(fileOffset);
                    }
                    fileOffset = index.get(fileName).offset();
                    System.out.println(" same hash and same file name : " +hashIndex.get(sha256));
                }
                else { // same hash and different file name
                    hashIndex.get(sha256).add(fileName);
                    if(freeSpaceOffset==0){ // if not used free space , truncate it
                        chDst.truncate(fileOffset);
                    }
                    fileOffset = index.get(hashIndex.get(sha256).getFirst()).offset();
                    System.out.println(" same hash and different file name : " +hashIndex.get(sha256));
                }

            }
            else{ // different hash
                if(removeHashIndex){ // different hash and same file name

                    long deletedFileOffset = index.get(fileName).offset();
                    String oldFileHash = index.get(fileName).dataHash();
                    List<String> fileNames = hashIndex.get(oldFileHash);
                    System.out.println("Files with same hash"+fileNames);
                    if(fileNames == null){ // if no files connected to old file's hash, remove the hash and update freespace info
                        hashIndex.remove(oldFileHash);
                        long freeSpace = getFreeSpace(deletedFileOffset );
                        freeIndex.computeIfAbsent(freeSpace, k->new ArrayList<Long>() ).add(deletedFileOffset);
                        System.out.println("added"+ freeIndex);
                    }
                    else { // if we have files connected to old file's hash, remove current filename from that list and update freespace info if list becomes empty
                        fileNames.remove(fileName);
                        if(fileNames.isEmpty()){
                            hashIndex.remove(oldFileHash);
                            long freeSpace = getFreeSpace(deletedFileOffset );
                            freeIndex.computeIfAbsent(freeSpace, k->new ArrayList<Long>() ).add(deletedFileOffset);
                            System.out.println("added"+ freeIndex);
                        }
                    }

                    List<String> newFileNames = new ArrayList<>( List.of(fileName));
                    hashIndex.put(sha256,  newFileNames);
                    System.out.println(" different hash and same file name : " +hashIndex.get(sha256));
                }
                else { // different hash and different file name
                    List<String> filenames = new ArrayList<>( List.of(fileName));
                    hashIndex.put(sha256,  filenames);
                    System.out.println(" different hash and different file name : " +hashIndex.get(sha256));
                }
                if(freeSpaceOffset>0){ // if free space is used, calculate remaining space after new file write and update it.
                    Map.Entry<Long,List<Long>> entry = freeIndex.ceilingEntry(freeSpaceInfo.sizeTowrite);
                    entry.getValue().removeFirst();
                    if(entry.getValue().isEmpty()){
                        freeIndex.remove(entry.getKey());
                    }
                    if(freeSpaceInfo.remainingSize>0){
                        freeIndex.computeIfAbsent(freeSpaceInfo.remainingSize, k->new ArrayList<Long>() ).add(freeSpaceInfo.remainingOffset);
                    }
                    freeIndex.remove(0L);// remove items with zero free space
                }

            }
            VaultIndex.VaultIndexItem vaultIndexItem = new VaultIndex.VaultIndexItem(fileName,fileOffset,fileSize,HexFormat.of().formatHex(bytes),modifiedTime,isCompressed);
            index.put(fileName, vaultIndexItem); // add new file entry in the new index array



            if(freeSpaceOffset > 0){ // if free space used, move filepointer to the old index address
                chDst.position(this.indexAddress);
            }
            // put the current file pointer as new index address
            long newIndexAddress = chDst.position();


            //  add index delimiter to the vault
            putNextStringIntoFile(chDst.position(), INDEX_DELIMITER,false,chDst);

            // Add the index length to the vault
            putNumberIntoFile(chDst.position(),index.size(),NumberType.INT,chDst);

            // write the index data to the vault
            putWholeIndexToFile(chDst.position(),index,chDst);

            putNextStringIntoFile(chDst.position(),INDEX_FREE_SPACE_DELIMITER,false,chDst);
            putNumberIntoFile(chDst.position(),this.vaultIndex.getFreeIndex().size(),NumberType.INT,chDst);
            putWholeFreeIndexToFile(chDst.position(),this.vaultIndex.getFreeIndex(),chDst);

            // write index end delimiter
            putNextStringIntoFile(chDst.position(),INDEX_END_DELIMITER,false,chDst);

            // Update the new index address at the beginning of the vault
            putNumberIntoFile(0,newIndexAddress,NumberType.LONG,chDst);

            // update the indexAddress to this object
            this.indexAddress = newIndexAddress;

        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        System.out.println("File added succesfully to the vault: " + fileName  );

    }



    public void extractFile(String fileName) throws IOException {
        Map<String, VaultIndex.VaultIndexItem> index = this.vaultIndex.getIndex();
        VaultIndex.VaultIndexItem indexItem = index.get(fileName);
        if(indexItem == null){
            System.out.println("No file with name "+ fileName+ " found in vault!");
            return;
        }
        long fileAddress = index.get(fileName).offset();
        try(FileChannel chSrc = FileChannel.open(this.filePath, StandardOpenOption.READ)){
            getStringFromFile(fileAddress,chSrc);
            Path newFilePath =   Path.of( this.filePath.toAbsolutePath().getParent().toString() , fileName )  ;
            try(FileChannel chDst = FileChannel.open(newFilePath , StandardOpenOption.WRITE,StandardOpenOption.CREATE)) {
                if(chDst.size() >0 ){
                    chDst.truncate(chDst.size());
                    System.out.println("Truncate");
                }
                long size = getNumberFromFile(chSrc.position(), NumberType.LONG, chSrc ).longValue();
                chSrc.transferTo(chSrc.position(),size,chDst);
            }
        }
    }


    public Set<String> getFiles() {
        return this.vaultIndex.getIndex().keySet();
    }

    public void printIndexData(){
          this.vaultIndex.printIndexData();
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
        int numberOfBytesWritten = 0;
        while (byteBufferEnd.hasRemaining()){ // sometimes write might not write all data, its better to check
            numberOfBytesWritten += ch.write(byteBufferEnd);
        }
        return numberOfBytesWritten;
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
        int numberOfBytesWritten = 0;
        while (byteBuffer.hasRemaining()){ // sometimes write might not write all data, its better to check
            numberOfBytesWritten+= ch.write(byteBuffer);
        }
        return numberOfBytesWritten;

    }

    public static Number getNumberFromFile(long position,  NumberType type, FileChannel ch) throws IOException{
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

    public static boolean getBooleanFromfile(long position, FileChannel ch) throws IOException{
        ch.position( position );
        ByteBuffer buffer = ByteBuffer.allocate(1);
        ch.read(buffer);
        buffer.flip();
        return buffer.get() != 0;
    }

    private int putBoolenIntoFile(long position, boolean data, FileChannel ch) throws IOException {
        ch.position(position);
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put( (byte) (data ? 1 : 0) );
        buffer.flip();
        return ch.write(buffer);
    }

    private int putVaultIndexItemIntoFile(long position, VaultIndex.VaultIndexItem indexItem,FileChannel ch) throws IOException {
        int totalBufferSize = getTotalBufferSize(indexItem);
        ByteBuffer byteBuffer = ByteBuffer.allocate(totalBufferSize);

        byteBuffer.putInt(indexItem.name().getBytes(StandardCharsets.UTF_8).length);
        byteBuffer.put(indexItem.name().getBytes(StandardCharsets.UTF_8));
        byteBuffer.putLong(indexItem.offset());
        byteBuffer.putLong(indexItem.dataLength());
        byteBuffer.putInt(indexItem.dataHash().getBytes(StandardCharsets.UTF_8).length);
        byteBuffer.put(indexItem.dataHash().getBytes(StandardCharsets.UTF_8));
        byteBuffer.putLong(indexItem.modifiedTime());
        byteBuffer.put( (byte) (indexItem.isCompressed() ? 1 : 0) );
        byteBuffer.flip();

        ch.position(position);
        int totalWrittenBytes = 0;
        while (byteBuffer.hasRemaining()){
            totalWrittenBytes += ch.write(byteBuffer);
        }

        return  totalWrittenBytes;
    }

    private int getTotalBufferSize(VaultIndex.VaultIndexItem indexItem){
        byte[] nameBytes = indexItem.name().getBytes(StandardCharsets.UTF_8);
        byte[] hashBytes = indexItem.dataHash().getBytes(StandardCharsets.UTF_8);
        int nameSize = Integer.BYTES + nameBytes.length;
        int offsetSize = Long.BYTES;
        int dataLengthSize = Long.BYTES;
        int hashSize = Integer.BYTES + hashBytes.length;
        int modifiedTimeSize = Long.BYTES;
        int isCompressedSize = 1;

        return nameSize + offsetSize + dataLengthSize + hashSize + modifiedTimeSize + isCompressedSize;

    }
    


    private int putWholeIndexToFile(long position, Map<String, VaultIndex.VaultIndexItem> indexItems, FileChannel ch) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(64 * 1024); // 64kb
        int totalBytesWritten =0;
        ch.position(position);
        for( VaultIndex.VaultIndexItem indexItem : indexItems.values() ){
            int itemBytes = getTotalBufferSize(indexItem);

            // if buffer dont have space to hold next item, flush it to file
            if(buffer.remaining() < itemBytes){
                while (buffer.hasRemaining()){
                    buffer.flip();
                    totalBytesWritten+= ch.write(buffer);
                }
            }

            buffer.putInt(indexItem.name().getBytes(StandardCharsets.UTF_8).length);
            buffer.put(indexItem.name().getBytes(StandardCharsets.UTF_8));
            buffer.putLong(indexItem.offset());
            buffer.putLong(indexItem.dataLength());
            buffer.putInt(indexItem.dataHash().getBytes(StandardCharsets.UTF_8).length);
            buffer.put(indexItem.dataHash().getBytes(StandardCharsets.UTF_8));
            buffer.putLong(indexItem.modifiedTime());
            buffer.put( (byte) (indexItem.isCompressed() ? 1 : 0) );

        }
        // flush remaining items to file
        while (buffer.hasRemaining()){
            buffer.flip();
            totalBytesWritten+= ch.write(buffer);
        }
        return totalBytesWritten;
    }

    private int putWholeFreeIndexToFile(long position, Map<Long,List<Long>> freeIndex, FileChannel ch) throws IOException{
        int size = freeIndex.size();

        int totalItems = Math.toIntExact(freeIndex.values().stream().mapToLong(List::size).sum());
        System.out.println("Freeindex size"+(size+totalItems));
        ByteBuffer buffer = ByteBuffer.allocate((size + totalItems) * 2 * Long.BYTES);
        for( Map.Entry<Long,List<Long>> e : freeIndex.entrySet()){
            for(Long v: e.getValue()){
                buffer.putLong(e.getKey());
                buffer.putLong(v);
            }
            System.out.println("Freeindex entry"+e);

        }
        buffer.flip();
        int bytesWritten = 0;
        ch.position(position);
        while (buffer.hasRemaining()){
            bytesWritten += ch.write(buffer);
        }
        return bytesWritten;
    }

    private long getFreeSpace(long offset) throws IOException{
        try(FileChannel chSrc = FileChannel.open(this.filePath, StandardOpenOption.READ)) {
            String filename = getStringFromFile(offset, chSrc);
            long size = getNumberFromFile(chSrc.position(), NumberType.LONG, chSrc ).longValue();
            return  Integer.BYTES + filename.getBytes(StandardCharsets.UTF_8).length + size;
        }
    }

    private long getFreeSpaceOffsetToWrite(long fileSize,String fileName){
        long sizeTowrite = fileSize + fileName.getBytes(StandardCharsets.UTF_8).length + Integer.BYTES;
        long resultOffset = 0;
        TreeMap<Long,List<Long>> freeIndex = this.vaultIndex.getFreeIndex();
        Map.Entry<Long,List<Long>> entry = freeIndex.ceilingEntry(sizeTowrite);
        if(entry != null){
           resultOffset = entry.getValue().isEmpty()? 0 : entry.getValue().getFirst();
        }
        return resultOffset ;
    }

    private FreeSpaceInfo getRemainingFreeSpaceAndOffsetInfo(long fileSize, String fileName){
        long sizeTowrite = fileSize + fileName.getBytes(StandardCharsets.UTF_8).length + Integer.BYTES;
        TreeMap<Long,List<Long>> freeIndex = this.vaultIndex.getFreeIndex();
        Map.Entry<Long,List<Long>> entry = freeIndex.ceilingEntry(sizeTowrite);

        long resultOffset = 0;
        long remainingSize =0;
        long remainingOffset = 0;
        if(entry != null){
            resultOffset = entry.getValue().isEmpty()? 0 : entry.getValue().getFirst();
            remainingSize = entry.getKey() - sizeTowrite;
            remainingOffset = resultOffset + sizeTowrite;
        }
        return new FreeSpaceInfo(sizeTowrite,resultOffset,remainingSize,remainingOffset);
    }


}
