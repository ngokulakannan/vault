package vault;

import vault.exceptions.InvalidVaultException;

import java.io.IOException;
import java.util.Arrays;

public class VaultHandler {
    public static void main(String[] args) throws IOException, InvalidVaultException {

        String path = "abc.vlt";
        Vault v =new Vault(path);
//        v.addFile("abc.txt");
//        v.addFile("xyz.txt");
//        v.addFile("abc.txt");
//        v.addFile("abc.txt");
//        v.addFile("xyz.txt");
//        v.addFile("xyz.txt");
//        v.addFile("xyz.txt");
//        v.addFile("abc.txt");
        Arrays.stream(v.getFiles()).forEach(System.out::println);
        v.getFilesWithMetadata().forEach((key, value) -> System.out.println(key + ":" + value));
        v.extractFile(0);

    }
}
