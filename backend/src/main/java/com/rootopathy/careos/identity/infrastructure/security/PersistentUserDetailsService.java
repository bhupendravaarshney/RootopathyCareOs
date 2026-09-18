package com.rootopathy.careos.identity.infrastructure.security;

import com.rootopathy.careos.identity.application.IdentitySecurityService;
import com.rootopathy.careos.identity.application.IdentityStore;
import com.rootopathy.careos.identity.domain.CredentialAccount;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public final class PersistentUserDetailsService implements UserDetailsService {
    private final IdentityStore identityStore;

    public PersistentUserDetailsService(IdentityStore identityStore) {
        this.identityStore = identityStore;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        var account = identityStore
                .findAccountByEmail(IdentitySecurityService.normalizeEmail(username))
                .filter(CredentialAccount::isActive)
                .orElseThrow(() -> new UsernameNotFoundException("Account is unavailable"));
        return new CareOsPrincipal(
                account.id(),
                account.email(),
                account.displayName(),
                account.passwordHash(),
                account.securityVersion(),
                account.mfaEnabled(),
                account.mfaRequired());
    }
}
