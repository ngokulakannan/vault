package vault.exceptions;

public class InvalidVaultException extends Exception {

    public InvalidVaultException(String cause){
        super("Invalid vault: " + cause);
    }
}
