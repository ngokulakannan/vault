package vault;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;


import static vault.Vault.getStringFromFile;
import static vault.constants.Constants.INDEX_DELIMITER;

public class VaultIndex {

    private Path filePath;

    public VaultIndex(Path filePath){
        this.filePath = filePath;
    }

    public long[] readIndex(long indexOffset ) throws IOException {
        try(FileChannel ch = FileChannel.open(this.filePath, StandardOpenOption.READ)){
            long indexStartPosition = indexOffset + INDEX_DELIMITER.length();
            ch.position( indexStartPosition );

            // read the index length from vault
            ByteBuffer indexLengthBuffer = ByteBuffer.allocate(Integer.BYTES);
            ch.read(indexLengthBuffer);
            indexLengthBuffer.flip();
            int indexLength = indexLengthBuffer.getInt();

            if(indexLength == 0){
                System.out.println("Empty Vault!!!");
                return new long[0];
            }

            // loop through index and get the data
            ByteBuffer indexbuffer = ByteBuffer.allocate(indexLength * Long.BYTES);
            ch.read(indexbuffer);
            indexbuffer.flip();
            long[] index = new long[indexLength];
            for (int i=0; i<indexLength;i++){
                index[i] = indexbuffer.getLong();
            }

            Arrays.stream(index).forEach(System.out::println);
            return index;

        }
    }

    public Map<Long,String> readIndexWithMetadata(long indexOffset) throws IOException{
        long[] index = readIndex(indexOffset);
        Map<Long,String> indexRecords = new TreeMap<>();

        try(FileChannel ch = FileChannel.open(this.filePath, StandardOpenOption.READ)){
            for(int i=0;i<index.length; i++) {
                indexRecords.put(index[i], getStringFromFile(index[i],ch));
            }
        }
        return indexRecords;
    }

}
