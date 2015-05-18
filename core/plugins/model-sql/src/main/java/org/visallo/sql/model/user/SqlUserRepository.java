package org.visallo.sql.model.user;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.v5analytics.simpleorm.SimpleOrmSession;
import org.visallo.core.config.Configuration;
import org.visallo.core.exception.VisalloException;
import org.visallo.core.model.notification.UserNotificationRepository;
import org.visallo.core.model.user.AuthorizationRepository;
import org.visallo.core.model.user.UserPasswordUtil;
import org.visallo.core.model.user.UserRepository;
import org.visallo.core.model.user.UserSessionCounterRepository;
import org.visallo.core.model.workQueue.WorkQueueRepository;
import org.visallo.core.user.ProxyUser;
import org.visallo.core.user.User;
import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import org.visallo.sql.model.HibernateSessionManager;
import org.visallo.sql.model.workspace.SqlWorkspace;
import org.visallo.web.clientapi.model.Privilege;
import org.visallo.web.clientapi.model.UserStatus;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.json.JSONObject;
import org.vertexium.Graph;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;

@Singleton
public class SqlUserRepository extends UserRepository {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(SqlUserRepository.class);
    private final AuthorizationRepository authorizationRepository;
    private final HibernateSessionManager sessionManager;
    private final Graph graph;

    @Inject
    public SqlUserRepository(
            final Configuration configuration,
            final SimpleOrmSession simpleOrmSession,
            final HibernateSessionManager sessionManager,
            final AuthorizationRepository authorizationRepository,
            final Graph graph,
            UserSessionCounterRepository userSessionCounterRepository,
            WorkQueueRepository workQueueRepository,
            UserNotificationRepository userNotificationRepository
    ) {
        super(configuration, simpleOrmSession, userSessionCounterRepository, workQueueRepository, userNotificationRepository);
        this.sessionManager = sessionManager;
        this.authorizationRepository = authorizationRepository;
        this.graph = graph;
    }

    @Override
    public User findByUsername(String username) {
        username = formatUsername(username);
        Session session = sessionManager.getSession();
        List<SqlUser> users = session.createQuery("select user from " + SqlUser.class.getSimpleName() + " as user where user.username=:username")
                .setParameter("username", username)
                .list();
        if (users.size() == 0) {
            return null;
        } else if (users.size() > 1) {
            throw new VisalloException("more than one user was returned");
        } else {
            return users.get(0);
        }
    }

    @Override
    public Iterable<User> find(int skip, int limit) {
        Session session = sessionManager.getSession();
        List<User> users = session.createQuery("select user from " + SqlUser.class.getSimpleName() + " as user")
                .setFirstResult(skip)
                .setMaxResults(limit)
                .list();
        return users;
    }

    @Override
    public Iterable<User> findByStatus(int skip, int limit, UserStatus status) {
        Session session = sessionManager.getSession();
        List<User> users = session.createQuery("select user from " + SqlUser.class.getSimpleName() + " as user where user.status = :status")
                .setParameter("status", status.toString())
                .setFirstResult(skip)
                .setMaxResults(limit)
                .list();
        return users;
    }

    @Override
    public User findById(String userId) {
        Session session = sessionManager.getSession();
        List<SqlUser> users = session.createQuery("select user from " + SqlUser.class.getSimpleName() + " as user where user.userId=:id")
                .setParameter("id", userId)
                .list();
        if (users.size() == 0) {
            return null;
        } else if (users.size() > 1) {
            throw new VisalloException("more than one user was returned");
        } else {
            return users.get(0);
        }
    }

    @Override
    public User addUser(String username, String displayName, String emailAddress, String password, String[] userAuthorizations) {
        username = formatUsername(username);
        displayName = displayName.trim();
        Session session = sessionManager.getSession();
        if (findByUsername(username) != null) {
            throw new VisalloException("User already exists");
        }

        Transaction transaction = null;
        SqlUser newUser = null;
        try {
            transaction = session.beginTransaction();
            newUser = new SqlUser();
            String id = "USER_" + graph.getIdGenerator().nextId();
            newUser.setUserId(id);
            newUser.setUsername(username);
            newUser.setDisplayName(displayName);
            newUser.setCreateDate(new Date());
            newUser.setEmailAddress(emailAddress);
            if (password != null && !password.equals("")) {
                byte[] salt = UserPasswordUtil.getSalt();
                byte[] passwordHash = UserPasswordUtil.hashPassword(password, salt);
                newUser.setPassword(salt, passwordHash);
            }
            newUser.setUserStatus(UserStatus.OFFLINE);
            newUser.setPrivilegesString(Privilege.toString(getDefaultPrivileges()));
            LOGGER.debug("add %s to user table", displayName);
            session.save(newUser);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new VisalloException("HibernateException while adding user", e);
        }

        afterNewUserAdded(newUser);

        return newUser;
    }

    @Override
    public void setPassword(User user, String password) {
        checkNotNull(password);
        Session session = sessionManager.getSession();
        if (user == null || user.getUserId() == null || findById(user.getUserId()) == null) {
            throw new VisalloException("User is not valid");
        }

        Transaction transaction = null;
        try {
            transaction = session.beginTransaction();
            byte[] salt = UserPasswordUtil.getSalt();
            byte[] passwordHash = UserPasswordUtil.hashPassword(password, salt);

            ((SqlUser) user).setPassword(salt, passwordHash);
            session.update(user);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new VisalloException("HibernateException while setting password", e);
        }
    }

    @Override
    public boolean isPasswordValid(User user, String password) {
        checkNotNull(password);
        if (user == null || user.getUserId() == null || (user.getUserId() != null && findById(user.getUserId()) == null)) {
            throw new VisalloException("User is not valid");
        }

        byte[] passwordSalt = ((SqlUser) user).getPasswordSalt();
        byte[] passwordHash = ((SqlUser) user).getPasswordHash();
        if (passwordSalt == null || passwordHash == null) {
            return false;
        }
        return UserPasswordUtil.validatePassword(password, passwordSalt, passwordHash);
    }

    @Override
    public void recordLogin(User user, String remoteAddr) {
        Session session = sessionManager.getSession();
        Transaction transaction = null;
        try {
            transaction = session.beginTransaction();

            Date currentLoginDate = user.getCurrentLoginDate();
            String currentLoginRemoteAddr = user.getCurrentLoginRemoteAddr();
            ((SqlUser) user).setPreviousLoginDate(currentLoginDate);
            ((SqlUser) user).setPreviousLoginRemoteAddr(currentLoginRemoteAddr);

            ((SqlUser) user).setCurrentLoginDate(new Date());
            ((SqlUser) user).setCurrentLoginRemoteAddr(remoteAddr);

            int loginCount = user.getLoginCount();
            ((SqlUser) user).setLoginCount(loginCount + 1);

            session.update(user);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new VisalloException("HibernateException while recording login", e);
        }
    }

    @Override
    public User setCurrentWorkspace(String userId, String workspaceId) {
        if (userId == null) {
            throw new VisalloException("UserId cannot be null");
        }

        Session session = sessionManager.getSession();
        Transaction transaction = null;
        SqlUser sqlUser = null;
        try {
            transaction = session.beginTransaction();
            sqlUser = (SqlUser) findById(userId);
            if (sqlUser == null) {
                throw new VisalloException("User does not exist");
            }
            List<SqlWorkspace> workspaces = session.createQuery
                    ("select workspace from " + SqlWorkspace.class.getSimpleName() + " as workspace where workspace.workspaceId=:id")
                    .setParameter("id", workspaceId)
                    .list();
            if (workspaces.size() == 0) {
                throw new VisalloException("Could not find workspace with id: " + workspaceId);
            }
            sqlUser.setCurrentWorkspace(workspaces.get(0));
            session.merge(sqlUser);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new VisalloException("HibernateException while setting current workspace", e);
        }
        return sqlUser;
    }

    @Override
    public String getCurrentWorkspaceId(String userId) {
        if (userId == null) {
            throw new VisalloException("UserId cannot be null");
        }

        Session session = sessionManager.getSession();
        try {
            SqlUser sqlUser = (SqlUser) findById(userId);
            if (sqlUser == null) {
                throw new VisalloException("User does not exist");
            }
            return sqlUser.getCurrentWorkspace() == null ? null : sqlUser.getCurrentWorkspace().getWorkspaceId();
        } catch (HibernateException e) {
            throw new VisalloException("HibernateException while getting current workspace", e);
        }
    }

    @Override
    public void setUiPreferences(User user, JSONObject preferences) {
        SqlUser sqlUser = sqlUser(user);
        Session session = sessionManager.getSession();
        Transaction transaction = null;
        try {
            transaction = session.beginTransaction();

            sqlUser.setUiPreferences(preferences);

            session.update(sqlUser);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new VisalloException("HibernateException while setting preferences", e);
        }
    }

    @Override
    public User setStatus(String userId, UserStatus status) {
        Session session = sessionManager.getSession();
        if (userId == null) {
            throw new VisalloException("UserId cannot be null");
        }

        Transaction transaction = null;
        SqlUser sqlUser;
        try {
            transaction = session.beginTransaction();
            sqlUser = (SqlUser) findById(userId);
            if (sqlUser == null) {
                throw new VisalloException("User does not exist");
            }
            sqlUser.setUserStatus(status);
            session.update(sqlUser);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new VisalloException("HibernateException while setting status", e);
        }
        return sqlUser;
    }

    @Override
    public void internalAddAuthorization(User user, String auth, User authUser) {
    }

    @Override
    public void internalRemoveAuthorization(User user, String auth, User authUser) {
    }

    @Override
    public org.vertexium.Authorizations getAuthorizations(User user, String... additionalAuthorizations) {
        return graph.createAuthorizations(new HashSet<String>());
    }

    @Override
    public void setDisplayName(User user, String displayName) {
        SqlUser sqlUser = sqlUser(user);
        Session session = sessionManager.getSession();
        Transaction transaction = null;
        try {
            transaction = session.beginTransaction();

            sqlUser.setDisplayName(displayName);

            session.update(sqlUser);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new VisalloException("HibernateException while setting display name", e);
        }
    }

    @Override
    public void setEmailAddress(User user, String emailAddress) {
        SqlUser sqlUser = sqlUser(user);
        Session session = sessionManager.getSession();
        Transaction transaction = null;
        try {
            transaction = session.beginTransaction();

            sqlUser.setEmailAddress(emailAddress);

            session.update(sqlUser);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new VisalloException("HibernateException while setting e-mail address", e);
        }
    }

    @Override
    public Set<Privilege> getPrivileges(User user) {
        return sqlUser(user).getPrivileges();
    }

    @Override
    protected void internalDelete(User user) {
        Session session = sessionManager.getSession();

        Transaction transaction = null;
        SqlUser sqlUser;
        try {
            transaction = session.beginTransaction();
            sqlUser = (SqlUser) findById(user.getUserId());
            if (sqlUser == null) {
                throw new VisalloException("User does not exist");
            }
            session.delete(sqlUser);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new VisalloException("HibernateException while deleting user", e);
        }
    }

    @Override
    protected void internalSetPrivileges(User user, Set<Privilege> privileges, User authUser) {
        Session session = sessionManager.getSession();

        Transaction transaction = null;
        SqlUser sqlUser;
        try {
            transaction = session.beginTransaction();
            sqlUser = (SqlUser) findById(user.getUserId());
            if (sqlUser == null) {
                throw new VisalloException("User does not exist");
            }
            sqlUser.setPrivilegesString(Privilege.toString(privileges));
            session.update(sqlUser);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new VisalloException("HibernateException while setting privileges", e);
        }
    }

    @Override
    public User findByPasswordResetToken(String token) {
        Session session = sessionManager.getSession();
        List<SqlUser> users = session.createQuery("select user from " + SqlUser.class.getSimpleName() + " as user where user.passwordResetToken = :token")
                .setParameter("token", token)
                .list();
        if (users.size() == 0) {
            return null;
        } else if (users.size() > 1) {
            throw new VisalloException("more than one user was returned");
        } else {
            return users.get(0);
        }
    }

    @Override
    public void setPasswordResetTokenAndExpirationDate(User user, String token, Date expirationDate) {
        Session session = sessionManager.getSession();
        if (user == null || user.getUserId() == null || findById(user.getUserId()) == null) {
            throw new VisalloException("User is not valid");
        }

        Transaction transaction = null;
        try {
            transaction = session.beginTransaction();
            ((SqlUser) user).setPasswordResetToken(token);
            ((SqlUser) user).setPasswordResetTokenExpirationDate(expirationDate);
            session.update(user);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new VisalloException("HibernateException while setting token and expiration date", e);
        }
    }

    @Override
    public void clearPasswordResetTokenAndExpirationDate(User user) {
        Session session = sessionManager.getSession();
        if (user == null || user.getUserId() == null || findById(user.getUserId()) == null) {
            throw new VisalloException("User is not valid");
        }

        Transaction transaction = null;
        try {
            transaction = session.beginTransaction();
            ((SqlUser) user).setPasswordResetToken(null);
            ((SqlUser) user).setPasswordResetTokenExpirationDate(null);
            session.update(user);
            transaction.commit();
        } catch (HibernateException e) {
            if (transaction != null) {
                transaction.rollback();
            }
            throw new VisalloException("HibernateException while clearing token and expiration date", e);
        }
    }

    private SqlUser sqlUser(User user) {
        if (user instanceof ProxyUser) {
            return (SqlUser) ((ProxyUser) user).getProxiedUser();
        } else {
            return (SqlUser) user;
        }
    }
}