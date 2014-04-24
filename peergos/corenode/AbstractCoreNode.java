package peergos.corenode;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;

import java.util.*;
import java.net.*;


public abstract class AbstractCoreNode
{
    public static final float FRAC_TOLERANCE = 0.001f;
    private static final long DEFAULT_FRAGMENT_LENGTH = 0x10000;

    /**
     * Maintains meta-data about fragments stored in the DHT,
     * the relationship between users and with whom user fragments
     * are shared.      
     */ 

    //
    // TODO: Usage, assume all fragments same size
    // TODO: share( String key, String sharer, String Shareee, ByteArrayWrapper b1, ByteArrayWrapper b2)
    // TODO: update key 
    //
    static class UserData
    {
        public static final int MAX_PENDING_FOLLOWERS = 100;

        private final Set<ByteArrayWrapper> followRequests;
        private final Set<UserPublicKey> followers;
        //private final Map<ByteArrayWrapper, ByteArrayWrapper> fragments;
        private final Map<UserPublicKey, Map<ByteArrayWrapper, ByteArrayWrapper> > fragments;

        UserData()
        {
            this.followRequests = new HashSet<ByteArrayWrapper>();
            this.followers = new HashSet<UserPublicKey>();
            this.fragments = new HashMap<UserPublicKey, Map<ByteArrayWrapper, ByteArrayWrapper> >();
        }
    }  

    static class StorageNodeState
    {
        private final Set<ByteArrayWrapper> fragmentHashesOnThisNode;
        private final String owner;
        private final Map<String, Float> storageFraction;
        private final InetSocketAddress address;
        StorageNodeState(String owner, InetSocketAddress address, Map<String, Float> fractions)
        {
            this.owner = owner;
            this.address = address;
            this.storageFraction = new HashMap<String,Float>(fractions);
            this.fragmentHashesOnThisNode = new HashSet();
        }

        public long getSize()
        {
            return calculateSize();
        }

        private long calculateSize()
        {
            return fragmentLength()*fragmentHashesOnThisNode.size();
        }

        public boolean addHash(byte[] hash)
        {
            return fragmentHashesOnThisNode.add(new ByteArrayWrapper(hash));
        }

        public int hashCode(){return address.hashCode();}
        public boolean equals(Object that)
        {
            if (! (that instanceof StorageNodeState))
                return false;

            return ((StorageNodeState) that).address.equals(this.address);
        }
    } 

    protected final Map<String, UserData> userMap;
    protected final Map<String, UserPublicKey> userNameToPublicKeyMap;
    protected final Map<UserPublicKey, String> userPublicKeyToNameMap;

    //
    // quota stuff
    //

    /*
    //
    // aims
    // 1. calculate how much storage space a user has donated to other users (and themselves)  (via 1 or more storage nodes) 
    // 2. calculate how much storage space a Storage node has available to which other users
    //
    //
    //map of username to list of storage nodes that are donating on behalf of this user  (with fraction)
    protected final Map<InetSocketAddress, (String owner, Map<(String user, float frac))> > storageState;
    //derivable from above map
    protected final Map<String, List<InetSocketAddress> > userStorageFactories;

    //set of fragments donated by this storage node 
    protected final Map<InetSocketAdress, Set<ByteArrayWrapper> > storageNodeDonations;
    */
    protected final Map<InetSocketAddress, StorageNodeState> storageStates;
    protected final Map<String, Set<StorageNodeState> > userStorageFactories;

    public AbstractCoreNode()
    {
        this.userMap = new HashMap<String, UserData>();
        this.userNameToPublicKeyMap = new HashMap<String, UserPublicKey>();
        this.userPublicKeyToNameMap = new HashMap<UserPublicKey, String>();

        this.storageStates = new HashMap<InetSocketAddress, StorageNodeState>();
        this.userStorageFactories =new HashMap<String, Set<StorageNodeState> > ();
    }

    public static long fragmentLength(){return DEFAULT_FRAGMENT_LENGTH;}

    public synchronized UserPublicKey getPublicKey(String username)
    {
        return userNameToPublicKeyMap.get(username);
    }

    /*
     * @param userKey X509 encoded public key
     * @param signedHash the SHA hash of bytes in the username, signed with the user private key 
     * @param username the username that is being claimed
     */
    public boolean addUsername(String username, byte[] encodedUserKey, byte[] signedHash)
    {
        UserPublicKey key = new UserPublicKey(encodedUserKey);

        if (! key.isValidSignature(signedHash, username.getBytes()))
            return false;

        return addUsername(username, key);
    }

    protected synchronized boolean addUsername(String username, UserPublicKey key)
    {
        if (userNameToPublicKeyMap.containsKey(username))
            return false;
        if (userPublicKeyToNameMap.containsKey(key))
            return false;

        userNameToPublicKeyMap.put(username, key); 
        userPublicKeyToNameMap.put(key, username); 
        userMap.put(username, new UserData());
        userStorageFactories.put(username, new HashSet());
        return true;
    }

    public synchronized boolean followRequest(String target, byte[] encodedSharingPublicKey)
    {
        UserData userData = userMap.get(target);
        if (userData == null)
            return false;
        if (userData.followRequests.size() > UserData.MAX_PENDING_FOLLOWERS)
            return false;
        userData.followRequests.add(new ByteArrayWrapper(encodedSharingPublicKey));
        return true;
    }

    public boolean removeFollowRequest(String target, byte[] data, byte[] signedHash)
    {
        synchronized (this) {
            UserData userData = userMap.get(target);
            if (userData == null)
                return false;
            ByteArrayWrapper baw = new ByteArrayWrapper(data);
            if (!userData.followRequests.contains(baw))
                return false;
            UserPublicKey us = userNameToPublicKeyMap.get(target);
            if (!us.isValidSignature(signedHash, data))
                return false;
            return userData.followRequests.remove(baw);
        }
    }

    /*
     * @param userKey X509 encoded key of user that wishes to add a friend
     * @param signedHash the SHA hash of userBencodedKey, signed with the user private key 
     * @param encodedFriendName the bytes of the friendname sined with userKey 
     */
    public boolean allowSharingKey(String username, byte[] encodedSharingPublicKey, byte[] signedHash)
    {
        UserPublicKey key = null; 
        synchronized(this)
        {
            key = userNameToPublicKeyMap.get(username);
        }
        
        if (key == null || ! key.isValidSignature(signedHash, encodedSharingPublicKey))
            return false;

        synchronized (this) {
            UserData userData = userMap.get(username);
            UserPublicKey sharingPublicKey = new UserPublicKey(encodedSharingPublicKey);
            if (userData == null)
                return false;
            if (userData.followers.contains(sharingPublicKey))
                return false;
            userData.followers.add(sharingPublicKey);
            userData.fragments.put(sharingPublicKey, new HashMap<ByteArrayWrapper, ByteArrayWrapper>());
        }
        return true;
    }

    protected synchronized boolean banSharingKey(UserPublicKey userKey, UserPublicKey sharingPublicKey)
    {
        UserData userData = userMap.get(userKey);

        if (userData == null)
            return false;
        
        return userData.followers.remove(sharingPublicKey);
    }
    
    /*
     * @param userKey X509 encoded key of user that wishes to add a fragment
     * @param signedHash the SHA hash of encodedFragmentData, signed with the user private key 
     * @param encodedFragmentData fragment meta-data encoded with userKey
     */ 
    
    //public boolean addFragment(byte[] userKey, byte[] signedHash, byte[] encodedFragmentData)
    public boolean addFragment(String username, byte[] encodedSharingPublicKey, byte[] fragmentData, byte[] sharingKeySignedHash)
    {
        UserPublicKey userKey = null;
        synchronized(this)
        {
            userKey = userNameToPublicKeyMap.get(username);
        }
        if (userKey == null)
            return false;
            
        UserPublicKey sharingKey = new UserPublicKey(encodedSharingPublicKey);
        synchronized(this)
        {
            if (!userMap.get(username).followers.contains(sharingKey))
                return false;
        }
          
        if (! sharingKey.isValidSignature(sharingKeySignedHash, fragmentData))
            return false;

        return addFragment(username, sharingKey, fragmentData);
    }

    protected synchronized boolean addFragment(String username, UserPublicKey sharingKey, byte[] fragmentData)
    {
         
        UserData userData = userMap.get(username);

        if (userData == null)
            return false;

        if (remainingStorage(username) < fragmentLength())
            return false;

        Map<ByteArrayWrapper, ByteArrayWrapper> fragments = userData.fragments.get(sharingKey);
        if (fragments == null)
            return false;

        byte[] hash = sharingKey.hash(fragmentData);
        ByteArrayWrapper hashW = new ByteArrayWrapper(hash);
        if (fragments.containsKey(hashW))
            return false;
        
        fragments.put(hashW, new ByteArrayWrapper(fragmentData));
        return true;
    }

    /*
     * @param userKey X509 encoded key of user that wishes to add a fragment
     * @param hash the hash of the fragment to be removed 
     * @param signedHash the SHA hash of hash, signed with the user private key 
     */ 
    //public boolean removeFragment(byte[] userKey, byte[] signedHash, byte[] hash)
    public boolean removeFragment(String username, byte[] encodedSharingKey, byte[] fragmentHash, byte[] userKeySignedHash, byte[] sharingKeySignedHash)
    {
        UserPublicKey userKey = null;
        UserPublicKey sharingKey = new UserPublicKey(encodedSharingKey);

        synchronized(this)
        {
            userKey = userNameToPublicKeyMap.get(username);
            if (userKey == null)
                return false;
            if (! userMap.get(username).followers.contains(sharingKey))
                return false;
        }
        if (! userKey.isValidSignature(fragmentHash, userKeySignedHash))
           return false; 

        if (! sharingKey.isValidSignature(fragmentHash, sharingKeySignedHash))
            return false;

        return removeFragment(username, sharingKey, fragmentHash);
    }

    protected synchronized boolean removeFragment(String username, UserPublicKey sharingKey, byte[] fragmentHash)
    {
        UserData userData = userMap.get(username);

        if (userData == null)
            return false;
        Map<ByteArrayWrapper, ByteArrayWrapper> fragments = userData.fragments.get(sharingKey);
        if (fragments == null)
            return false;

        return fragments.remove(new ByteArrayWrapper(fragmentHash)) != null;
    }

    /*
     * @param userKey X509 encoded key of user to be removed 
     * @param username to be removed 
     * @param signedHash the SHA hash of the bytes that make up username, signed with the user private key 
     *
     */

    public boolean removeUsername(String username, byte[] userKey, byte[] signedHash)
    {
        UserPublicKey key = new UserPublicKey(userKey);

        if (! Arrays.equals(key.hash(username),key.unsignMessage(signedHash)))
            return false;

        return removeUsername(key, username);
    }

    protected synchronized boolean removeUsername(UserPublicKey key, String username)
    {
        userPublicKeyToNameMap.remove(key);
        userMap.remove(key);
        return userNameToPublicKeyMap.remove(username) != null;
    }

    /*
     * @param userKey X509 encoded key of user that wishes to share a fragment 
     * @param signedHash the SHA hash of userKey, signed with the user private key 
     */
    public synchronized Iterator<UserPublicKey> getSharingKeys(String username)
    {
        UserPublicKey userKey = userNameToPublicKeyMap.get(username);
        
        if (userKey == null)
            return null;
            
        UserData userData = userMap.get(username);
        return Collections.unmodifiableCollection(userData.fragments.keySet()).iterator();
        
    } 

    /*
     * @param userKey X509 encoded key of user that wishes to share a fragment 
     * @param signedHash the SHA hash of userKey, signed with the user private key 
     */
    public synchronized Iterator<ByteArrayWrapper> getFragments(String username, byte[] encodedSharingKey)
    {
        UserPublicKey userKey = userNameToPublicKeyMap.get(username);
        if (userKey == null)
            return null;
        
        UserData userData = userMap.get(username);
        
        Map<ByteArrayWrapper, ByteArrayWrapper> sharedFragments = userData.fragments.get(new UserPublicKey(encodedSharingKey));
        
        if (sharedFragments == null)
            return null;
            
        return Collections.unmodifiableCollection(sharedFragments.values()).iterator();
    }

    private boolean addStorageNodeState(String owner, InetSocketAddress address)
    {
        Map<String, Float> fracs = new HashMap<String, Float>();
        fracs.put(owner, 1.f);
        return addStorageNodeState(owner, address, fracs);
    }
    private boolean addStorageNodeState(String owner, InetSocketAddress address, Map<String, Float> fracs)
    {
        //
        // validate map entries
        //
        float totalFraction = 0.f;
        for (Float frac: fracs.values())
            if (frac < 0)
                return false;
            else
                totalFraction += frac;

        if (totalFraction -1 > FRAC_TOLERANCE)
            return false;

        StorageNodeState state = new StorageNodeState(owner, address, fracs);
        return addStorageNodeState(state);
    }

    private synchronized boolean addStorageNodeState(StorageNodeState state)
    {

        if (! userNameToPublicKeyMap.containsKey(state.owner))
            return false;
        if (storageStates.containsKey(state.address))
            return false;

        for (String user: state.storageFraction.keySet())
            if (! userNameToPublicKeyMap.containsKey(user))
                return false;

        storageStates.put(state.address, state);

        for (String user: state.storageFraction.keySet())
        {
            if (userStorageFactories.get(user) == null)
                userStorageFactories.put(user, new HashSet<StorageNodeState>());

            userStorageFactories.get(user).add(state);
        }
        return true;
    }

    public synchronized boolean registerFragment(String recipient, InetSocketAddress node, byte[] hash)
    {
        if (!userStorageFactories.containsKey(recipient))
            return false;
        if (!storageStates.containsKey(node))
            addStorageNodeState(recipient, node);
        StorageNodeState donor = storageStates.get(node);
        return donor.addHash(hash);
    }

    public synchronized long getQuota(String user)
    {
        if (! userNameToPublicKeyMap.containsKey(user))
            return -1l;

        Set<StorageNodeState> storageStates = userStorageFactories.get(user);
        if (storageStates == null)
            return 0l;
        long quota = 0l;
        
        for (StorageNodeState state: storageStates)
            quota += state.getSize()* state.storageFraction.get(user);

        return quota;    
    }

    public synchronized long getUsage(String username)
    {
        UserPublicKey userKey = userNameToPublicKeyMap.get(username);
        if (userKey == null)
            return -1l;
        
       long usage = 0l;
       for (Map<ByteArrayWrapper, ByteArrayWrapper> fragmentsMap: userMap.get(username).fragments.values())
           usage += fragmentsMap.size() * fragmentLength();

        return usage;
    }

    private synchronized long remainingStorage(String user)
    {
        long quota = getQuota(user);
        long usage = getUsage(user);

        return Math.max(0, quota - usage);
    }
}