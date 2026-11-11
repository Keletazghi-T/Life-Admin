package com.lifeadmin.service;
import com.lifeadmin.dto.*;
import com.lifeadmin.entity.User;
import com.lifeadmin.repository.*;
import com.lifeadmin.security.AccountPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
@Service
public class AccountManagementService {
    private final UserRepository users;
    private final DocumentRepository documents;
    private final DocumentManagementService management;
    private final ReceiptRepository receipts;
    private final ReceiptService receiptService;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transactions;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(AccountManagementService.class);
    public AccountManagementService(UserRepository users,DocumentRepository documents,DocumentManagementService management,PasswordEncoder passwords,PlatformTransactionManager manager,ReceiptRepository receipts,ReceiptService receiptService) {
        this.receipts=receipts;this.receiptService=receiptService;this.users=users;this.documents=documents;this.management=management;this.passwords=passwords;transactions=new TransactionTemplate(manager);
    }
    private User verify(AccountPrincipal principal,String password) {
        var user=users.lockById(principal.id()).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please log in again."));
        if(user.getSecurityVersion()!=principal.securityVersion()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Please log in again.");
        if(password.getBytes(StandardCharsets.UTF_8).length>72 || !passwords.matches(password,user.getPasswordHash()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Incorrect current password.");
        return user;
    }
    public void changePassword(AccountPrincipal principal,ChangePasswordRequest input) {
        if(!input.newPassword().equals(input.confirmNewPassword())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"New passwords do not match.");
        if(input.newPassword().getBytes(StandardCharsets.UTF_8).length>72) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password must be at most 72 UTF-8 bytes.");
        transactions.executeWithoutResult(tx->{
            var user=verify(principal,input.currentPassword());
            if(user.isDeletionPending()) throw new ResponseStatusException(HttpStatus.CONFLICT,"Account deletion is incomplete. Retry Delete permanently.");
            user.changePassword(passwords.encode(input.newPassword()));
        });
    }
    public void delete(AccountPrincipal principal,DeleteAccountRequest input) {
        // Durable fence before touching any files. Uploads take the same user lock and reject this state.
        transactions.executeWithoutResult(tx->verify(principal,input.currentPassword()).markDeletionPending());
        try {
            // Each document has its own committed cleanup marker; failed or concurrent retries remain safe.
            for(var id:documents.findIdsByUserId(principal.id())) {
                if(documents.existsById(id)) management.delete(principal.id(),id);
            }
            for(var id:receipts.findIdsByUserId(principal.id())) {
                if(receipts.existsById(id)) receiptService.delete(principal.id(),id);
            }
            transactions.executeWithoutResult(tx->{
                var user=users.lockById(principal.id()).orElseThrow();
                if(!documents.findIdsByUserId(principal.id()).isEmpty() || !receipts.findIdsByUserId(principal.id()).isEmpty()) throw new IllegalStateException("Cleanup incomplete");
                users.delete(user);users.flush();
            });
        } catch(RuntimeException e) {
            log.error("Account cleanup incomplete; retry required");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Unable to finish deleting account. Open Settings and retry Delete permanently.");
        }
    }
}
