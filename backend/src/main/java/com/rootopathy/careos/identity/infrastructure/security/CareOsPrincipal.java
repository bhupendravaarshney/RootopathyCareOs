package com.rootopathy.careos.identity.infrastructure.security;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class CareOsPrincipal implements UserDetails, CredentialsContainer, Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID id;
    private final String email;
    private final String displayName;
    private final long securityVersion;
    private final boolean mfaRequired;
    private String passwordHash;

    public CareOsPrincipal(
            UUID id,
            String email,
            String displayName,
            String passwordHash,
            long securityVersion,
            boolean mfaRequired) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.securityVersion = securityVersion;
        this.mfaRequired = mfaRequired;
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    public long securityVersion() {
        return securityVersion;
    }

    public boolean mfaRequired() {
        return mfaRequired;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        var authority = mfaRequired ? CareOsAuthorities.MFA_PENDING : CareOsAuthorities.AUTHENTICATED;
        return List.of(new SimpleGrantedAuthority(authority));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }
}
