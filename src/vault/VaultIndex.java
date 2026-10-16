package vault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;


import static vault.Vault.*;
import static vault.constants.Constants.INDEX_DELIMITER;
import static vault.constants.Constants.INDEX_FREE_SPACE_DELIMITER;

public class VaultIndex {

    private Path filePath;
    private Map<String,VaultIndexItem> index  ;
    private Map<String, List<String>> hashIndex  ;
    private TreeMap<Long,List<Long>> freeIndex;
    public record VaultIndexItem(String name, long offset,long dataLength, String dataHash, long modifiedTime, boolean isCompressed ){}

    public VaultIndex(Path filePath, long indexOffset) throws IOException {

        this.filePath = filePath;
        this.index = new TreeMap<>();
        this.hashIndex = new HashMap<>();
        this.freeIndex = new TreeMap<>();
        readIndexWithFileName(indexOffset);

    }

    public Map<String,VaultIndexItem> getIndex(){
        return this.index;
    }

    public  Map<String,List<String>> getHashIndex(){
        return this.hashIndex;
    }

    public TreeMap<Long,List<Long>> getFreeIndex(){
        return this.freeIndex;
    }


    private void readIndexWithFileName(long indexOffset ) throws IOException {
        try(FileChannel ch = FileChannel.open(this.filePath, StandardOpenOption.READ)){
            long indexStartPosition = indexOffset + INDEX_DELIMITER.length();
            // read the index length from vault
            int indexLength = getNumberFromFile(indexStartPosition, Vault.NumberType.INT,ch).intValue();
            if(indexLength == 0){
                System.out.println("Empty Vault!!!");
                return ;
            }
            // loop through index and get the data
            for (int i=0; i<indexLength;i++){
                VaultIndexItem indexItem = readIndexItem(ch);
                this.index.put(indexItem.name(),indexItem);
                this.hashIndex.computeIfAbsent(indexItem.dataHash(), (k)-> new ArrayList<String>()  ).add(indexItem.name);

            }
            indexStartPosition = ch.position() + INDEX_FREE_SPACE_DELIMITER.length();
            // read the index length from vault
            indexLength = getNumberFromFile(indexStartPosition, Vault.NumberType.INT,ch).intValue();
            if(indexLength == 0){
                System.out.println("No Empty Space In Vault!!!");
                return ;
            }
            for (int i=0; i<indexLength;i++){
                ByteBuffer buffer = ByteBuffer.allocate(2 * Long.BYTES);
                ch.read(buffer);
                buffer.flip();
                long key = buffer.getLong();
                long value = buffer.getLong();
                freeIndex.computeIfAbsent(key, k->new ArrayList<Long>() ).add(value);
                System.out.println("Free Space in: " + key);
            }

        }
    }

    public VaultIndexItem readIndexItem(FileChannel ch) throws IOException {
        String name = getStringFromFile(ch.position(), ch);
        long offset = getNumberFromFile(ch.position(), Vault.NumberType.LONG,ch).longValue();
        long dataLength = getNumberFromFile(ch.position(), Vault.NumberType.LONG,ch).longValue();
        String dataHash = getStringFromFile(ch.position(),ch);
        long modifiedTime = getNumberFromFile(ch.position(),Vault.NumberType.LONG, ch).longValue();
        boolean isCompressed = getBooleanFromfile(ch.position(),ch);
        return new VaultIndexItem(name,offset,dataLength,dataHash,modifiedTime,isCompressed);
    }

    public void printIndexData(){
        for(Map.Entry<String,List<String>> e: this.hashIndex.entrySet()){
            System.out.println("----------------");
            System.out.println(e.getKey() +" : " + e.getValue());
            for(String filename: e.getValue()){
                if(this.index.containsKey(filename)){
                    System.out.println("name: "+filename);
                }

            }
            System.out.println("----------------");
        }

    }



}
