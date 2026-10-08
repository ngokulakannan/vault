package vault;

import vault.exceptions.InvalidVaultException;


import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public class Vault {
    private final Path filePath;
    private final String HEADER_DELIMITER = "GK";
    private final String INDEX_DELIMITER = "VAULT_INDEX";


    public Vault(String path) throws IOException, InvalidVaultException {
        Path filePath = Paths.get(path);
        int headerLength = 8 + HEADER_DELIMITER.length(); // 8 is the number of bytes of long (where index address is stored)
        if(Files.exists(filePath)){
            this.filePath = filePath;

            try(FileChannel ch = FileChannel.open(filePath)){
                // Header validation
                ByteBuffer buffer = ByteBuffer.allocate(headerLength);
                System.out.println(buffer);
                ch.read(buffer);
                buffer.flip();
                long indexAddress = buffer.getLong();
                System.out.println("index address:" + indexAddress);
                byte[] delimiterBytes = new byte[HEADER_DELIMITER.length()];
                buffer.get(8,delimiterBytes);
                checkInvalidVault("header",delimiterBytes,"invalid headers");

                // index validation
                ByteBuffer indexBuffer = ByteBuffer.allocate(INDEX_DELIMITER.length());
                System.out.println("Channel position:" + ch.position());
                ch.position(indexAddress);
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
            System.out.println("file create : " + this.filePath.toAbsolutePath());
            ByteBuffer headerBuffer = ByteBuffer.allocate(headerLength);
            headerBuffer.putLong(headerLength);
            byte[] delimiter = HEADER_DELIMITER.getBytes(StandardCharsets.UTF_8);
            headerBuffer.put(delimiter);
            try(FileChannel ch = FileChannel.open(this.filePath, StandardOpenOption.READ,StandardOpenOption.WRITE)){
                headerBuffer.flip();
                int headerWrittenBytes = ch.write(headerBuffer);
                System.out.println("header written bytes: " + headerWrittenBytes);
                ByteBuffer buffer = ByteBuffer.allocate(INDEX_DELIMITER.getBytes(StandardCharsets.UTF_8).length);
                buffer.put(INDEX_DELIMITER.getBytes(StandardCharsets.UTF_8));
                buffer.flip();
                int indexWrittenBytes = ch.write(buffer);
                System.out.println("index written bytes: " + indexWrittenBytes);

            }

        }


    }

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
