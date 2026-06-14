package store.anybodycan.meshtalk;

/**
 * A person we've seen on the mesh, identified by their stable app id (NOT a MAC).
 * Name can change, lastSeen tracks when we last heard from them (chat or presence),
 * and hops is how many relay hops away they currently are (1 = direct neighbour, 0 = unknown).
 */
public class Peer {
    public final String id;
    public String name;
    public long lastSeen;
    public int hops;

    public Peer(String id, String name, long lastSeen, int hops) {
        this.id = id;
        this.name = name;
        this.lastSeen = lastSeen;
        this.hops = hops;
    }
}
