package com.lifeadmin.service;
import com.lifeadmin.entity.User;
import com.lifeadmin.dto.RegisterRequest;
import com.lifeadmin.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.nio.charset.StandardCharsets;
@Service
public class AccountService {
    private final UserRepository users; private final PasswordEncoder passwords;
    public AccountService(UserRepository users,PasswordEncoder passwords) { this.users=users; this.passwords=passwords; }
    public void register(RegisterRequest input) {
        if(input.password().getBytes(StandardCharsets.UTF_8).length>72)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Password must be at most 72 UTF-8 bytes.");
        if(users.existsByEmail(input.email())) throw duplicate();
        try { users.saveAndFlush(new User(input.email(),passwords.encode(input.password()))); }
        catch(DataIntegrityViolationException e) { throw duplicate(); }
    }
    private ResponseStatusException duplicate() { return new ResponseStatusException(HttpStatus.CONFLICT,"An account could not be created with this email. Try logging in."); }
}
