package com.vnator.adminshop.money;

import com.vnator.adminshop.AdminShop;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class MoneyManager extends SavedData {

    private static final String COMPOUND_TAG_NAME = "adminshop_ledger";

    private static final int MAX_ACCOUNTS = 8;

    // SORTED SETS AND MAPS
    // The set of all BankAccounts
    private final Set<BankAccount> accountSet = new HashSet<>();

    // A map with ownerUUID and accountID
    private final Map<String, Integer> accountsOwned = new HashMap<>();

    // A map with ownerUUID and Map<accountID, BankAccount>
    private final Map<String, Map<Integer, BankAccount>> sortedAccountMap = new HashMap<>();

    // A map with playerUUID and a List of every BankAccount player is member or owner of
    private final Map<String, List<BankAccount>> sharedAccounts = new HashMap<>();


    public Map<String, List<BankAccount>> getSharedAccounts() {
        return sharedAccounts;
    }

    public Set<BankAccount> getAccountSet() {
        return accountSet;
    }

    /**
     * Creates a new account for owner with given members, if owner still has free account slots.
     * @param owner the UUID of the account owner
     * @param members the set of the account's members' UUIDs, must contain the owner itself
     * @return the ID of the new account, or -1 if no account was created
     */
    public int CreateAccount(String owner, Set<String> members) {

        if(!members.contains(owner)) {
            AdminShop.LOGGER.warn("Member set does not contain owner, adding.");
            members.add(owner);
        }

        if (accountsOwned.get(owner) >= MAX_ACCOUNTS) {
            AdminShop.LOGGER.error("Owner has reached max accounts limit!");
            return -1;
        }
        int newId = -1;

        // Find free account ID, -1 if not found
        for (int i = 1; i < (MAX_ACCOUNTS+1); i++) {
            if(!sortedAccountMap.get(owner).containsKey(i)) {
                newId = i;
                break;
            }
        }

        if (newId == -1) {
            AdminShop.LOGGER.error("Could not find free account ID for owner!");
            return newId;
        }

        // Create new account and add to relevant sets/maps
        BankAccount newAccount = new BankAccount(owner, members, newId, 0L);
        accountSet.add(newAccount);
        accountsOwned.put(owner, newId);
        sortedAccountMap.get(owner).put(newId, newAccount);
        newAccount.getMembers().forEach(member -> {
            sharedAccounts.get(member).add(newAccount);
        });

        // return new account ID
        return newId;
    }

    //"Singleton" getter
    public static MoneyManager get(Level checkLevel){
        if(checkLevel.isClientSide()){
            throw new RuntimeException("Don't access this client-side!");
        }
        MinecraftServer serv = ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = serv.getLevel(Level.OVERWORLD);
        assert level != null;
        DimensionDataStorage storage = level.getDataStorage();
        return storage.computeIfAbsent(MoneyManager::new, MoneyManager::new, "moneymanager");
    }

    /**
     * Gets BankAccount given owner and ID
     * @param owner the owner UUID
     * @param id the account's ID
     * @return the specified BankAccount
     */
    public BankAccount getBankAccount(String owner, int id) {
        if(!sortedAccountMap.containsKey(owner) && id != 1) {
            AdminShop.LOGGER.error("User doesn't have any shared bank accounts!");
            return getBankAccount(owner, 1);
        } else if(!sortedAccountMap.containsKey(owner) && id == 1) {
            AdminShop.LOGGER.info("Creating personal bank account.");
            HashMap<Integer, BankAccount> newPlayerMap = new HashMap<>();
            BankAccount newAccount = new BankAccount(owner);
            newPlayerMap.put(1, newAccount);
            sortedAccountMap.put(owner, newPlayerMap);
            if (!accountsOwned.containsKey(owner)) {
                accountsOwned.put(owner, 1);
            } else {
                accountsOwned.put(owner, accountsOwned.get(owner)+1);
            }
            if (!sharedAccounts.containsKey(owner)) {
                sharedAccounts.put(owner, new ArrayList<>());
            }
            sharedAccounts.get(owner).add(newAccount);
            accountSet.add(sortedAccountMap.get(owner).get(id));
            setDirty();
        }
        return sortedAccountMap.get(owner).get(id);
    }

    /**
     * Deletes bank account from memory. Can't delete personal accounts (id 1)
     * @param owner the owner UUID
     * @param id the account's ID
     * @return true if successful, false if not
     */
    public boolean deleteBankAccount(String owner, int id) {
        // Check if trying to delete personal account
        if (id == 1) {
            AdminShop.LOGGER.error("Cannot delete personal account!");
            return false;
        }
        // Check if account exists
        if (!existsBankAccount(owner, id)) {
            AdminShop.LOGGER.error("Trying to delete an account which does not exist!");
            return false;
        }
        // Get account
        BankAccount toDelete = getBankAccount(owner, id);

        // Delete account from set and relevant maps
        accountSet.remove(toDelete);
        accountsOwned.put(owner, accountsOwned.get(owner) - 1);
        sortedAccountMap.get(owner).remove(id);
        toDelete.getMembers().forEach(member -> sharedAccounts.get(member).remove(toDelete));
        return true;
    }

    /**
     * Checks if said bank account exists
     * @param owner Owner UUID
     * @param id Account ID
     * @return true if account exists, false otherwise
     */
    public boolean existsBankAccount(String owner, int id) {
        return sortedAccountMap.containsKey(owner) && sortedAccountMap.get(owner).containsKey(id);
    }

    //Money getters/setters

    /**
     * Gets player's personal account balance
     * @param player the player's UUID
     * @return the player's personal account balance
     * @deprecated Use getBalance(String player, Int id) instead
     */
    public long getBalance(String player){
        AdminShop.LOGGER.warn("getBalance(String player) is deprecated.");
        return  getBankAccount(player, 1).getBalance();
    }

    public long getBalance(String player, int id){
        return  getBankAccount(player, id).getBalance();
    }


    public boolean addBalance(String player, long amount){
        setDirty();
        return getBankAccount(player, 1).addBalance(amount);
    }
    public boolean addBalance(String player, int id, long amount){
        setDirty();
        return getBankAccount(player, id).addBalance(amount);
    }

    public boolean subtractBalance(String player, long amount){
        setDirty();
        return getBankAccount(player, 1).subtractBalance(amount);
    }
    public boolean subtractBalance(String player, int id, long amount){
        setDirty();
        return getBankAccount(player, id).subtractBalance(amount);
    }

    public boolean setBalance(String player, long amount){
        if(amount < 0) return false;
        getBankAccount(player, 1).setBalance(amount);
        setDirty();
        return true;
    }

    //Constructors
    public MoneyManager(){}

    public MoneyManager(CompoundTag tag){
        if (tag.contains(COMPOUND_TAG_NAME)) {
            ListTag ledger = tag.getList(COMPOUND_TAG_NAME, 10);
            accountSet.clear();
            accountsOwned.clear();
            sortedAccountMap.clear();
            sharedAccounts.clear();
            ledger.forEach((accountTag) -> {
                BankAccount bankAccount = BankAccount.deserializeTag((CompoundTag) accountTag);
                accountSet.add(bankAccount);

                // add to sorted accounts maps
                String owner = bankAccount.getOwner();
                int id = bankAccount.getId();
                if (accountsOwned.containsKey(owner)) {
                    accountsOwned.put(bankAccount.getOwner(), accountsOwned.get(owner) + 1);
                } else {
                    accountsOwned.put(owner, 1);
                }

                if (sortedAccountMap.containsKey(owner)) {
                    sortedAccountMap.get(owner).put(id, bankAccount);
                } else {
                    HashMap<Integer, BankAccount> newPlayerMap = new HashMap<>();
                    newPlayerMap.put(1, bankAccount);
                    sortedAccountMap.put(owner, newPlayerMap);
                }

                // create shared accounts list
                bankAccount.getMembers().forEach(member -> {
                    List<BankAccount> sharedAccountsList;
                    if (!sharedAccounts.containsKey(member)) {
                        sharedAccountsList = new ArrayList<>();

                    } else {
                        sharedAccountsList = sharedAccounts.get(member);
                    }
                    sharedAccountsList.add(bankAccount);
                    sharedAccounts.put(member, sharedAccountsList);
                });
            });
        }
    }

    @Override
    public @NotNull CompoundTag save(CompoundTag tag) {
        ListTag ledger = new ListTag();

        accountSet.forEach((account) -> {
            CompoundTag bankAccountTag = account.serializeTag();
            ledger.add(bankAccountTag);
        });
        tag.put(COMPOUND_TAG_NAME, ledger);
        return tag;
    }
}
