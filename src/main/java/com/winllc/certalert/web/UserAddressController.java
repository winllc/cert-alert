package com.winllc.certalert.web;

import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.UserEmailAlias;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.UserEmailAliasRepository;
import com.winllc.certalert.security.DirectoryPrincipal;
import com.winllc.certalert.security.DirectoryPrincipalResolver;
import com.winllc.certalert.security.SecurityProperties;
import com.winllc.certalert.service.ResourceNotFoundException;
import com.winllc.certalert.service.UserEmailAliasService;
import com.winllc.certalert.web.dto.AddAliasRequest;
import com.winllc.certalert.web.dto.UserAddresses;
import com.winllc.certalert.web.dto.UserEmailAliasRow;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The addresses a person answers to.
 *
 * <p>Reading returns both lists: what the directory publishes, which is a cached copy and
 * not editable here, and what was added here to bind them to servers the directory's
 * addresses do not reach.
 */
@RestController
@RequestMapping("/api/v1")
public class UserAddressController {

    private final UserEmailAliasService aliasService;
    private final UserEmailAliasRepository aliasRepository;
    private final DirectoryUserRepository userRepository;
    private final SecurityProperties securityProperties;

    public UserAddressController(
            UserEmailAliasService aliasService,
            UserEmailAliasRepository aliasRepository,
            DirectoryUserRepository userRepository,
            SecurityProperties securityProperties) {
        this.aliasService = aliasService;
        this.aliasRepository = aliasRepository;
        this.userRepository = userRepository;
        this.securityProperties = securityProperties;
    }

    @GetMapping("/users/{id}/addresses")
    @Transactional(readOnly = true)
    public UserAddresses addresses(@PathVariable Long id, Authentication authentication) {
        DirectoryUser user = userRepository.findById(id).orElseThrow(() -> ResourceNotFoundException.user(id));
        List<UserEmailAliasRow> added = aliasService.list(id).stream()
                .map(alias -> UserEmailAliasRow.from(alias, sharedWith(alias, id)))
                .toList();
        return new UserAddresses(published(user), added, mayEdit(authentication));
    }

    @PostMapping("/users/{id}/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    public UserEmailAliasRow add(
            @PathVariable Long id, @Valid @RequestBody AddAliasRequest request, Authentication authentication) {

        UserEmailAlias alias =
                aliasService.add(id, request.address(), request.kind(), request.label(), nameOf(authentication));
        return UserEmailAliasRow.from(alias, sharedWith(alias, id));
    }

    @DeleteMapping("/users/{id}/addresses/{aliasId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable Long id, @PathVariable Long aliasId) {
        aliasService.remove(id, aliasId);
    }

    /** The five the directory may publish, in the order the mapping reads them. */
    private List<String> published(DirectoryUser user) {
        Set<String> addresses = new LinkedHashSet<>();
        for (String address : new String[] {
            user.getEmail(), user.getIcEmail(), user.getInternetEmail(),
            user.getNiprnetEmail(), user.getSiprnetEmail(), user.getMail()
        }) {
            if (address != null && !address.isBlank()) {
                addresses.add(address);
            }
        }
        return new ArrayList<>(addresses);
    }

    /** How many other people answer to the same address; for a list, that is the point of it. */
    private int sharedWith(UserEmailAlias alias, Long userId) {
        return (int) aliasRepository.findUserIdsByAddress(alias.getAddress()).stream()
                .filter(other -> !other.equals(userId))
                .count();
    }

    private boolean mayEdit(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        if (securityProperties.getContactEditors() == SecurityProperties.ContactEditors.AUTHENTICATED) {
            return true;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> DirectoryPrincipalResolver.ROLE_ADMIN.equals(authority.getAuthority()));
    }

    private String nameOf(Authentication authentication) {
        if (authentication == null) {
            return null;
        }
        if (authentication.getPrincipal() instanceof DirectoryPrincipal principal) {
            return principal.getUsername();
        }
        return authentication.getName();
    }
}
