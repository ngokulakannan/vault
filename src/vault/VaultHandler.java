package vault;

import vault.exceptions.InvalidVaultException;

import java.io.IOException;

public class VaultHandler {
    public static void main(String[] args) throws IOException, InvalidVaultException {

        String path = "abc.vlt";
        Vault v =new Vault(path);
        v.addFile("xyz.txt");

    }
}
