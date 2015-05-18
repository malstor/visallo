package org.visallo.vertexium.model.user;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.v5analytics.simpleorm.SimpleOrmContext;
import com.v5analytics.simpleorm.SimpleOrmSession;
import org.visallo.core.config.Configuration;
import org.visallo.core.exception.VisalloException;
import org.visallo.core.model.notification.UserNotificationRepository;
import org.visallo.core.model.ontology.Concept;
import org.visallo.core.model.ontology.OntologyRepository;
import org.visallo.core.model.properties.VisalloProperties;
import org.visallo.core.model.user.*;
import org.visallo.core.model.workQueue.WorkQueueRepository;
import org.visallo.core.security.VisalloVisibility;
import org.visallo.core.user.SystemUser;
import org.visallo.core.user.User;
import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import org.visallo.web.clientapi.model.Privilege;
import org.visallo.web.clientapi.model.UserStatus;
import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;
import org.vertexium.Graph;
import org.vertexium.Vertex;
import org.vertexium.VertexBuilder;
import org.vertexium.util.ConvertingIterable;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.vertexium.util.IterableUtils.singleOrDefault;
import static org.vertexium.util.IterableUtils.toArray;

@Singleton
public class VertexiumUserRepository extends UserRepository {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(VertexiumUserRepository.class);
    private final AuthorizationRepository authorizationRepository;
    private Graph graph;
    private String userConceptId;
    private org.vertexium.Authorizations authorizations;
    private final Cache<String, Set<String>> userAuthorizationCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.SECONDS)
            .build();
    private final Cache<String, Set<Privilege>> userPrivilegesCache = CacheBuilder.newBuilder()
            .expireAfterWrite(15, TimeUnit.SECONDS)
            .build();

    @Inject
    public VertexiumUserRepository(
            final Configuration configuration,
            final SimpleOrmSession simpleOrmSession,
            final AuthorizationRepository authorizationRepository,
            final Graph graph,
            final OntologyRepository ontologyRepository,
            UserSessionCounterRepository userSessionCounterRepository,
            WorkQueueRepository workQueueRepository,
            UserNotificationRepository userNotificationRepository
    ) {
        super(configuration, simpleOrmSession, userSessionCounterRepository, workQueueRepository, userNotificationRepository);
        this.authorizationRepository = authorizationRepository;
        this.graph = graph;

        authorizationRepository.addAuthorizationToGraph(VISIBILITY_STRING);
        authorizationRepository.addAuthorizationToGraph(VisalloVisibility.SUPER_USER_VISIBILITY_STRING);

        Concept userConcept = ontologyRepository.getOrCreateConcept(null, USER_CONCEPT_IRI, "visalloUser", null);
        userConceptId = userConcept.getIRI();

        Set<String> authorizationsSet = new HashSet<>();
        authorizationsSet.add(VISIBILITY_STRING);
        authorizationsSet.add(VisalloVisibility.SUPER_USER_VISIBILITY_STRING);
        this.authorizations = graph.createAuthorizations(authorizationsSet);
    }

    private VertexiumUser createFromVertex(Vertex user) {
        if (user == null) {
            return null;
        }

        String userId = user.getId();
        String username = UserVisalloProperties.USERNAME.getPropertyValue(user);
        String displayName = UserVisalloProperties.DISPLAY_NAME.getPropertyValue(user);
        String emailAddress = UserVisalloProperties.EMAIL_ADDRESS.getPropertyValue(user);
        Date createDate = UserVisalloProperties.CREATE_DATE.getPropertyValue(user);
        Date currentLoginDate = UserVisalloProperties.CURRENT_LOGIN_DATE.getPropertyValue(user);
        String currentLoginRemoteAddr = UserVisalloProperties.CURRENT_LOGIN_REMOTE_ADDR.getPropertyValue(user);
        Date previousLoginDate = UserVisalloProperties.PREVIOUS_LOGIN_DATE.getPropertyValue(user);
        String previousLoginRemoteAddr = UserVisalloProperties.PREVIOUS_LOGIN_REMOTE_ADDR.getPropertyValue(user);
        int loginCount = UserVisalloProperties.LOGIN_COUNT.getPropertyValue(user, 0);
        String[] authorizations = toArray(getAuthorizations(user), String.class);
        SimpleOrmContext simpleOrmContext = getSimpleOrmContext(authorizations);
        UserStatus userStatus = UserStatus.valueOf(UserVisalloProperties.STATUS.getPropertyValue(user));
        Set<Privilege> privileges = Privilege.stringToPrivileges(UserVisalloProperties.PRIVILEGES.getPropertyValue(user));
        String currentWorkspaceId = UserVisalloProperties.CURRENT_WORKSPACE.getPropertyValue(user);
        JSONObject preferences = UserVisalloProperties.UI_PREFERENCES.getPropertyValue(user);
        String passwordResetToken = UserVisalloProperties.PASSWORD_RESET_TOKEN.getPropertyValue(user);
        Date passwordResetTokenExpirationDate = UserVisalloProperties.PASSWORD_RESET_TOKEN_EXPIRATION_DATE.getPropertyValue(user);

        LOGGER.debug("Creating user from UserRow. username: %s", username);
        return new VertexiumUser(
                userId,
                username,
                displayName,
                emailAddress,
                createDate,
                currentLoginDate,
                currentLoginRemoteAddr,
                previousLoginDate,
                previousLoginRemoteAddr,
                loginCount,
                simpleOrmContext,
                userStatus,
                privileges,
                currentWorkspaceId,
                preferences,
                passwordResetToken,
                passwordResetTokenExpirationDate
        );
    }

    @Override
    public User findByUsername(String username) {
        username = formatUsername(username);
        return createFromVertex(singleOrDefault(graph.query(authorizations)
                .has(UserVisalloProperties.USERNAME.getPropertyName(), username)
                .has(VisalloProperties.CONCEPT_TYPE.getPropertyName(), userConceptId)
                .vertices(), null));
    }

    @Override
    public Iterable<User> find(int skip, int limit) {
        return new ConvertingIterable<Vertex, User>(graph.query(authorizations)
                .has(VisalloProperties.CONCEPT_TYPE.getPropertyName(), userConceptId)
                .skip(skip)
                .limit(limit)
                .vertices()) {
            @Override
            protected User convert(Vertex vertex) {
                return createFromVertex(vertex);
            }
        };
    }

    @Override
    public Iterable<User> findByStatus(int skip, int limit, UserStatus status) {
        return new ConvertingIterable<Vertex, User>(graph.query(authorizations)
                .has(VisalloProperties.CONCEPT_TYPE.getPropertyName(), userConceptId)
                .has(UserVisalloProperties.STATUS.getPropertyName(), status.toString())
                .skip(skip)
                .limit(limit)
                .vertices()) {
            @Override
            protected User convert(Vertex vertex) {
                return createFromVertex(vertex);
            }
        };
    }

    @Override
    public User findById(String userId) {
        return createFromVertex(findByIdUserVertex(userId));
    }

    public Vertex findByIdUserVertex(String userId) {
        return graph.getVertex(userId, authorizations);
    }

    @Override
    public User addUser(String username, String displayName, String emailAddress, String password, String[] userAuthorizations) {
        username = formatUsername(username);
        displayName = displayName.trim();
        User existingUser = findByUsername(username);
        if (existingUser != null) {
            throw new VisalloException("duplicate username");
        }

        String authorizationsString = StringUtils.join(userAuthorizations, ",");

        byte[] salt = UserPasswordUtil.getSalt();
        byte[] passwordHash = UserPasswordUtil.hashPassword(password, salt);

        String id = "USER_" + graph.getIdGenerator().nextId();
        VertexBuilder userBuilder = graph.prepareVertex(id, VISIBILITY.getVisibility());

        VisalloProperties.CONCEPT_TYPE.setProperty(userBuilder, userConceptId, VISIBILITY.getVisibility());
        UserVisalloProperties.USERNAME.setProperty(userBuilder, username, VISIBILITY.getVisibility());
        UserVisalloProperties.DISPLAY_NAME.setProperty(userBuilder, displayName, VISIBILITY.getVisibility());
        UserVisalloProperties.CREATE_DATE.setProperty(userBuilder, new Date(), VISIBILITY.getVisibility());
        UserVisalloProperties.PASSWORD_SALT.setProperty(userBuilder, salt, VISIBILITY.getVisibility());
        UserVisalloProperties.PASSWORD_HASH.setProperty(userBuilder, passwordHash, VISIBILITY.getVisibility());
        UserVisalloProperties.STATUS.setProperty(userBuilder, UserStatus.OFFLINE.toString(), VISIBILITY.getVisibility());
        UserVisalloProperties.AUTHORIZATIONS.setProperty(userBuilder, authorizationsString, VISIBILITY.getVisibility());
        UserVisalloProperties.PRIVILEGES.setProperty(userBuilder, Privilege.toString(getDefaultPrivileges()), VISIBILITY.getVisibility());

        if (emailAddress != null) {
            UserVisalloProperties.EMAIL_ADDRESS.setProperty(userBuilder, emailAddress, VISIBILITY.getVisibility());
        }

        User user = createFromVertex(userBuilder.save(this.authorizations));
        graph.flush();

        afterNewUserAdded(user);

        return user;
    }

    @Override
    public void setPassword(User user, String password) {
        byte[] salt = UserPasswordUtil.getSalt();
        byte[] passwordHash = UserPasswordUtil.hashPassword(password, salt);
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        UserVisalloProperties.PASSWORD_SALT.setProperty(userVertex, salt, VISIBILITY.getVisibility(), authorizations);
        UserVisalloProperties.PASSWORD_HASH.setProperty(userVertex, passwordHash, VISIBILITY.getVisibility(), authorizations);
        graph.flush();
    }

    @Override
    public boolean isPasswordValid(User user, String password) {
        try {
            Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
            return UserPasswordUtil.validatePassword(password, UserVisalloProperties.PASSWORD_SALT.getPropertyValue(userVertex), UserVisalloProperties.PASSWORD_HASH.getPropertyValue(userVertex));
        } catch (Exception ex) {
            throw new RuntimeException("error validating password", ex);
        }
    }

    @Override
    public void recordLogin(User user, String remoteAddr) {
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);

        Date currentLoginDate = UserVisalloProperties.CURRENT_LOGIN_DATE.getPropertyValue(userVertex);
        if (currentLoginDate != null) {
            UserVisalloProperties.PREVIOUS_LOGIN_DATE.setProperty(userVertex, currentLoginDate, VISIBILITY.getVisibility(), authorizations);
        }

        String currentLoginRemoteAddr = UserVisalloProperties.CURRENT_LOGIN_REMOTE_ADDR.getPropertyValue(userVertex);
        if (currentLoginRemoteAddr != null) {
            UserVisalloProperties.PREVIOUS_LOGIN_REMOTE_ADDR.setProperty(userVertex, currentLoginRemoteAddr, VISIBILITY.getVisibility(), authorizations);
        }

        UserVisalloProperties.CURRENT_LOGIN_DATE.setProperty(userVertex, new Date(), VISIBILITY.getVisibility(), authorizations);
        UserVisalloProperties.CURRENT_LOGIN_REMOTE_ADDR.setProperty(userVertex, remoteAddr, VISIBILITY.getVisibility(), authorizations);

        int loginCount = UserVisalloProperties.LOGIN_COUNT.getPropertyValue(userVertex, 0);
        UserVisalloProperties.LOGIN_COUNT.setProperty(userVertex, loginCount + 1, VISIBILITY.getVisibility(), authorizations);

        graph.flush();
    }

    @Override
    public User setCurrentWorkspace(String userId, String workspaceId) {
        User user = findById(userId);
        checkNotNull(user, "Could not find user: " + userId);
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        UserVisalloProperties.CURRENT_WORKSPACE.setProperty(userVertex, workspaceId, VISIBILITY.getVisibility(), authorizations);
        graph.flush();
        return user;
    }

    @Override
    public String getCurrentWorkspaceId(String userId) {
        User user = findById(userId);
        checkNotNull(user, "Could not find user: " + userId);
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        return UserVisalloProperties.CURRENT_WORKSPACE.getPropertyValue(userVertex);
    }

    @Override
    public void setUiPreferences(User user, JSONObject preferences) {
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        UserVisalloProperties.UI_PREFERENCES.setProperty(userVertex, preferences, VISIBILITY.getVisibility(), authorizations);
        graph.flush();
    }

    @Override
    public User setStatus(String userId, UserStatus status) {
        VertexiumUser user = (VertexiumUser) findById(userId);
        checkNotNull(user, "Could not find user: " + userId);
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        UserVisalloProperties.STATUS.setProperty(userVertex, status.toString(), VISIBILITY.getVisibility(), authorizations);
        graph.flush();
        user.setUserStatus(status);
        return user;
    }

    @Override
    public void internalAddAuthorization(User user, String auth, User authUser) {
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        Set<String> authorizationSet = getAuthorizations(userVertex);
        if (authorizationSet.contains(auth)) {
            return;
        }
        authorizationSet.add(auth);

        this.authorizationRepository.addAuthorizationToGraph(auth);

        String authorizationsString = StringUtils.join(authorizationSet, ",");
        UserVisalloProperties.AUTHORIZATIONS.setProperty(userVertex, authorizationsString, VISIBILITY.getVisibility(), authorizations);
        graph.flush();
        userAuthorizationCache.invalidate(user.getUserId());
    }

    @Override
    public void internalRemoveAuthorization(User user, String auth, User authUser) {
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        Set<String> authorizationSet = getAuthorizations(userVertex);
        if (!authorizationSet.contains(auth)) {
            return;
        }
        authorizationSet.remove(auth);
        String authorizationsString = StringUtils.join(authorizationSet, ",");
        UserVisalloProperties.AUTHORIZATIONS.setProperty(userVertex, authorizationsString, VISIBILITY.getVisibility(), authorizations);
        graph.flush();
        userAuthorizationCache.invalidate(user.getUserId());
    }

    @Override
    public org.vertexium.Authorizations getAuthorizations(User user, String... additionalAuthorizations) {
        Set<String> userAuthorizations;
        if (user instanceof SystemUser) {
            userAuthorizations = new HashSet<>();
            userAuthorizations.add(VisalloVisibility.SUPER_USER_VISIBILITY_STRING);
        } else {
            userAuthorizations = userAuthorizationCache.getIfPresent(user.getUserId());
        }
        if (userAuthorizations == null) {
            Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
            userAuthorizations = getAuthorizations(userVertex);
            userAuthorizationCache.put(user.getUserId(), userAuthorizations);
        }

        Set<String> authorizationsSet = new HashSet<>(userAuthorizations);
        Collections.addAll(authorizationsSet, additionalAuthorizations);
        return graph.createAuthorizations(authorizationsSet);
    }

    public static Set<String> getAuthorizations(Vertex userVertex) {
        String authorizationsString = UserVisalloProperties.AUTHORIZATIONS.getPropertyValue(userVertex);
        if (authorizationsString == null) {
            return new HashSet<>();
        }
        String[] authorizationsArray = authorizationsString.split(",");
        if (authorizationsArray.length == 1 && authorizationsArray[0].length() == 0) {
            authorizationsArray = new String[0];
        }
        HashSet<String> authorizations = new HashSet<>();
        for (String s : authorizationsArray) {
            // Accumulo doesn't like zero length strings. they shouldn't be in the auth string to begin with but this just protects from that happening.
            if (s.trim().length() == 0) {
                continue;
            }

            authorizations.add(s);
        }
        return authorizations;
    }

    @Override
    public void setDisplayName(User user, String displayName) {
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        UserVisalloProperties.DISPLAY_NAME.setProperty(userVertex, displayName, VISIBILITY.getVisibility(), authorizations);
        graph.flush();
    }

    @Override
    public void setEmailAddress(User user, String emailAddress) {
        Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
        UserVisalloProperties.EMAIL_ADDRESS.setProperty(userVertex, emailAddress, VISIBILITY.getVisibility(), authorizations);
        graph.flush();
    }

    @Override
    public Set<Privilege> getPrivileges(User user) {
        Set<Privilege> privileges;
        if (user instanceof SystemUser) {
            return Privilege.ALL;
        } else {
            privileges = userPrivilegesCache.getIfPresent(user.getUserId());
        }
        if (privileges == null) {
            Vertex userVertex = graph.getVertex(user.getUserId(), authorizations);
            privileges = getPrivileges(userVertex);
            userPrivilegesCache.put(user.getUserId(), privileges);
        }
        return privileges;
    }

    @Override
    protected void internalDelete(User user) {
        Vertex userVertex = findByIdUserVertex(user.getUserId());
        graph.softDeleteVertex(userVertex, authorizations);
        graph.flush();
    }

    @Override
    protected void internalSetPrivileges(User user, Set<Privilege> privileges, User authUser) {
        Vertex userVertex = findByIdUserVertex(user.getUserId());
        UserVisalloProperties.PRIVILEGES.setProperty(userVertex, Privilege.toString(privileges), VISIBILITY.getVisibility(), authorizations);
        graph.flush();
        userPrivilegesCache.invalidate(user.getUserId());
    }

    private Set<Privilege> getPrivileges(Vertex userVertex) {
        return Privilege.stringToPrivileges(UserVisalloProperties.PRIVILEGES.getPropertyValue(userVertex));
    }

    @Override
    public User findByPasswordResetToken(String token) {
        return createFromVertex(singleOrDefault(graph.query(authorizations)
                .has(UserVisalloProperties.PASSWORD_RESET_TOKEN.getPropertyName(), token)
                .has(VisalloProperties.CONCEPT_TYPE.getPropertyName(), userConceptId)
                .vertices(), null));
    }

    @Override
    public void setPasswordResetTokenAndExpirationDate(User user, String token, Date expirationDate) {
        Vertex userVertex = findByIdUserVertex(user.getUserId());
        UserVisalloProperties.PASSWORD_RESET_TOKEN.setProperty(userVertex, token, VISIBILITY.getVisibility(), authorizations);
        UserVisalloProperties.PASSWORD_RESET_TOKEN_EXPIRATION_DATE.setProperty(userVertex, expirationDate, VISIBILITY.getVisibility(), authorizations);
        graph.flush();
    }

    @Override
    public void clearPasswordResetTokenAndExpirationDate(User user) {
        Vertex userVertex = findByIdUserVertex(user.getUserId());
        UserVisalloProperties.PASSWORD_RESET_TOKEN.removeProperty(userVertex, authorizations);
        UserVisalloProperties.PASSWORD_RESET_TOKEN_EXPIRATION_DATE.removeProperty(userVertex, authorizations);
        graph.flush();
    }
}