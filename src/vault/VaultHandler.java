package vault;

import vault.exceptions.InvalidVaultException;

import java.io.IOException;

public class VaultHandler {
    public static void main(String[] args) throws IOException, InvalidVaultException {

        String path = "abc.vlt";
        Vault v =new Vault(path);
        v.addFile("abc.txt");
        v.addFile("xyz.txt");
        v.addFile("cba.txt");
        v.addFile("abc.txt");
        v.addFile("zyx.txt");
        v.addFile("./a/abc.txt");
        v.addFile("./a/cba.txt");
//        v.addFile("xyz.txt");
//        v.addFile("abc.txt");
        v.getFiles().forEach(System.out::println);
//        v.getFilesWithMetadata().forEach((key, value) -> System.out.println(key + ":" + value));
        v.printIndexData();

        v.extractFile("xyz.txt");

    }
}
