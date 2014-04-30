package peergos.corenode;

import peergos.crypto.*;
import peergos.util.ArrayOps;
import peergos.util.ByteArrayWrapper;

import java.util.*;
import java.net.*;
import java.io.*;


public abstract class AbstractCoreNode
{
    public static final int PORT = 9999;
    public static final float FRAC_TOLERANCE = 0.001f;
    private static final long DEFAULT_FRAGMENT_LENGTH = 0x10000;

    /**
     * Maintains meta-data about metadata stored in the DHT,
     * the relationship between users and with whom user metadata
     * are shared.      
     */ 

    //
    // TODO: Usage, assume all metadata same size
    // TODO: share( String key, String sharer, String Shareee, ByteArrayWrapper b1, ByteArrayWrapper b2)
    // TODO: update key 
    //
    public static class MetadataBlob
    {
        ByteArrayWrapper metadata;
        byte[] fragmentHashes;

        public MetadataBlob(ByteArrayWrapper metadata, byte[] fragmentHashes)
        {
            this.metadata = metadata;// 32 bytes AES key + 16 bytes IV + Y bytes (pointer to next blob) + 256 bytes filename + Z bytes pointer to parent
            this.fragmentHashes = fragmentHashes;
        }

        public boolean containsHash(byte[] hash)
        {
            for (int i=0; i < fragmentHashes.length/UserPublicKey.HASH_SIZE; i++)
                if (Arrays.equals(hash, Arrays.copyOfRange(fragmentHashes, i*UserPublicKey.HASH_SIZE, i*UserPublicKey.HASH_SIZE+UserPublicKey.HASH_SIZE)))
                    return true;
            return false;
        }
    }

    static class UserData
    {
        public static final int MAX_PENDING_FOLLOWERS = 100;

        private final Set<ByteArrayWrapper> followRequests;
        private final Set<UserPublicKey> followers;
        //private final Map<ByteArrayWrapper, ByteArrayWrapper> metadata;
        private final Map<UserPublicKey, Map<ByteArrayWrapper, MetadataBlob> > metadata;

        UserData()
        {
            this.followRequests = new HashSet<ByteArrayWrapper>();
            this.followers = new HashSet<UserPublicKey>();
            this.metadata = new HashMap<UserPublicKey, Map<ByteArrayWrapper, MetadataBlob> >();
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

    private final Map<String, UserData> userMap;
    private final Map<String, UserPublicKey> userNameToPublicKeyMap;
    private final Map<UserPublicKey, String> userPublicKeyToNameMap;

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
    private final Map<InetSocketAddress, (String owner, Map<(String user, float frac))> > storageState;
    //derivable from above map
    private final Map<String, List<InetSocketAddress> > userStorageFactories;

    //set of fragments donated by this storage node 
    private final Map<InetSocketAdress, Set<ByteArrayWrapper> > storageNodeDonations;
    */
    private final Map<InetSocketAddress, StorageNodeState> storageStates;
    private final Map<String, Set<StorageNodeState> > userStorageFactories;

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

    public synchronized String getUsername(byte[] encodedUserKey)
    {
        UserPublicKey key = new UserPublicKey(encodedUserKey);
        String name = userPublicKeyToNameMap.get(key);
        if (name == null)
            name = "";
        return name;
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

    private synchronized boolean addUsername(String username, UserPublicKey key)
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
            userData.metadata.put(sharingPublicKey, new HashMap<ByteArrayWrapper, MetadataBlob>());
        }
        return true;
    }

    public boolean banSharingKey(String username, byte[] encodedsharingPublicKey, byte[] signedHash)
    {
        UserPublicKey key = null;
        synchronized(this)
        {
            key = userNameToPublicKeyMap.get(username);
        }

        if (key == null || ! key.isValidSignature(signedHash,encodedsharingPublicKey))
            return false;

        UserPublicKey sharingPublicKey = new UserPublicKey(encodedsharingPublicKey);

       synchronized(this)
       {
        UserData userData = userMap.get(username);
        if (userData == null)
           return false; 
        return userData.followers.remove(sharingPublicKey);
       }
    }
    
    /*
     * @param userKey X509 encoded key of user that wishes to add a fragment
     * @param signedHash the SHA hash of encodedFragmentData, signed with the user private key 
     * @param encodedFragmentData fragment meta-data encoded with userKey
     */ 
    
    //public boolean addMetadataBlob(byte[] userKey, byte[] signedHash, byte[] encodedFragmentData)
    public boolean addMetadataBlob(String username, byte[] encodedSharingPublicKey, byte[] mapKey, byte[] metadataBlob, byte[] sharingKeySignedHash)
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
          
        if (! sharingKey.isValidSignature(sharingKeySignedHash, metadataBlob))
            return false;

        return addMetadataBlob(username, sharingKey, mapKey, metadataBlob);
    }

    private synchronized boolean addMetadataBlob(String username, UserPublicKey sharingKey, byte[] mapKey, byte[] metadataBlob)
    {
         
        UserData userData = userMap.get(username);

        if (userData == null)
            return false;

        if (remainingStorage(username) < fragmentLength())
            return false;

        Map<ByteArrayWrapper, MetadataBlob> fragments = userData.metadata.get(sharingKey);
        if (fragments == null)
            return false;

        ByteArrayWrapper keyW = new ByteArrayWrapper(mapKey);
        if (fragments.containsKey(keyW))
            return false;
        
        fragments.put(keyW, new MetadataBlob(new ByteArrayWrapper(metadataBlob), null));
        return true;
    }

    public boolean addFragmentHashes(String username, byte[] encodedSharingPublicKey, byte[] mapKey, byte[] metadataBlob, byte[] allHashes, byte[] sharingKeySignedHash)
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
        if (remainingStorage(username) < fragmentLength())
            return false;

        byte[] concat = ArrayOps.concat(mapKey, metadataBlob, allHashes);
        if (! sharingKey.isValidSignature(sharingKeySignedHash, concat))
            return false;
        UserData userData = userMap.get(username);

        if (userData == null)
            return false;

        Map<ByteArrayWrapper, MetadataBlob> fragments = userData.metadata.get(sharingKey);
        if (fragments == null)
            return false;

        MetadataBlob meta = fragments.get(new ByteArrayWrapper(mapKey));
        if (meta == null)
            return false;

        // add hashes
        meta.fragmentHashes = allHashes;
        return true;
    }


    // should delete fragments from dht as well (once that call exists)
    public boolean removeMetadataBlob(String username, byte[] encodedSharingKey, byte[] mapKey, byte[] sharingKeySignedMapKey)
    {
        UserPublicKey userKey;
        UserPublicKey sharingKey = new UserPublicKey(encodedSharingKey);

        synchronized(this)
        {
            userKey = userNameToPublicKeyMap.get(username);
            if (userKey == null)
                return false;
            if (! userMap.get(username).followers.contains(sharingKey))
                return false;
        }

        if (! sharingKey.isValidSignature(sharingKeySignedMapKey, mapKey))
            return false;

        return removeMetaDataBlob(username, sharingKey, mapKey);
    }

    private synchronized boolean removeMetaDataBlob(String username, UserPublicKey sharingKey, byte[] mapKey)
    {
        UserData userData = userMap.get(username);

        if (userData == null)
            return false;
        Map<ByteArrayWrapper, MetadataBlob> fragments = userData.metadata.get(sharingKey);
        if (fragments == null)
            return false;

        return fragments.remove(new ByteArrayWrapper(mapKey)) != null;
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

    private synchronized boolean removeUsername(UserPublicKey key, String username)
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
        return Collections.unmodifiableCollection(userData.metadata.keySet()).iterator();
        
    } 

    public synchronized MetadataBlob getMetadataBlob(String username, byte[] encodedSharingKey, byte[] mapkey)
    {
        UserPublicKey userKey = userNameToPublicKeyMap.get(username);
        if (userKey == null)
            return null;

        UserData userData = userMap.get(username);
        Map<ByteArrayWrapper, MetadataBlob> sharedFragments = userData.metadata.get(new UserPublicKey(encodedSharingKey));

        ByteArrayWrapper key = new ByteArrayWrapper(mapkey);
        if ((sharedFragments == null) || (!sharedFragments.containsKey(key)))
            return null;
        return sharedFragments.get(key);
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

    public synchronized boolean isFragmentAllowed(String owner, byte[] encodedSharingKey, byte[] mapkey, byte[] hash)
    {
        UserData userData = userMap.get(owner);
        UserPublicKey sharingPublicKey = new UserPublicKey(encodedSharingKey);
        if (userData == null)
            return false;
        if (!userData.followers.contains(sharingPublicKey))
            return false;
        MetadataBlob blob = userData.metadata.get(sharingPublicKey).get(new ByteArrayWrapper(mapkey));
        if (blob == null)
            return false;

        return blob.containsHash(hash);
    }

    public synchronized boolean registerFragmentStorage(String spaceDonor, InetSocketAddress node, String owner, byte[] encodedSharingKey, byte[] hash, byte[] signedKeyPlusHash)
    {
        if (!userStorageFactories.containsKey(spaceDonor))
            return false;
        if (!storageStates.containsKey(node))
            addStorageNodeState(spaceDonor, node);

        StorageNodeState donor = storageStates.get(node);

        // verify signature
        UserData userData = userMap.get(owner);
        UserPublicKey sharingPublicKey = new UserPublicKey(encodedSharingKey);
        if (userData == null)
            return false;
        if (!userData.followers.contains(sharingPublicKey))
            return false;
        byte[] keyAndHash = ArrayOps.concat(encodedSharingKey, hash);
        if (!sharingPublicKey.isValidSignature(signedKeyPlusHash, keyAndHash))
            return false;

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
       for (Map<ByteArrayWrapper, MetadataBlob> fragmentsMap: userMap.get(username).metadata.values())
           for (MetadataBlob blob: fragmentsMap.values())
               usage += blob.fragmentHashes.length/UserPublicKey.HASH_SIZE * fragmentLength();

        return usage;
    }



    private synchronized long remainingStorage(String user)
    {
        long quota = getQuota(user);
        long usage = getUsage(user);

        return Math.max(0, quota - usage);
    }

    public abstract void close() throws IOException;

    public static AbstractCoreNode getDefault()
    {
        return new AbstractCoreNode() {
            @Override
            public void close() throws IOException {

            }
        };
    }
}
