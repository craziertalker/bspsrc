/*
 ** 2013 May 23
 **
 ** The author disclaims copyright to this source code.  In place of
 ** a legal notice, here is a blessing:
 **    May you do good and not evil.
 **    May you find forgiveness for yourself and forgive others.
 **    May you share freely, never taking more than you give.
 */
package info.ata4.bspsrc.modules;

import info.ata4.bsplib.BspFileReader;
import info.ata4.bsplib.entity.Entity;
import info.ata4.bspsrc.VmfWriter;
import info.ata4.bspsrc.modules.entity.Camera;
import info.ata4.log.LogUtils;
import info.ata4.util.AlphanumComparator;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * VMF metadata control class.
 * 
 * @author Nico Bergemann <barracuda415 at yahoo.de>
 */
public class VmfMeta extends ModuleDecompile {

    // logger
    private static final Logger L = LogUtils.getLogger();

    // UID mappings
    private Map<Integer, Integer> faceUIDs = new HashMap<>();
    private Map<Integer, Integer> origFaceUIDs = new HashMap<>();
    private Map<Short, Integer> dispinfoUIDs = new HashMap<>();

    // UID blacklist
    private Set<Integer> uidbl = new HashSet<>();

    // VMF unique ID
    private int uid = 0;

    // reserved visgroup ids. Main use for nmrih objectives visgroups
    private Map<List<String>, Integer> reservedVisgroups = new HashMap<>();
    // number for indexing every visgroup incrementally
    private int visgroupIndex = 0;
    // visgroup root node. Will not actualy be written in the final vmf but rather serves as the root node which contains every visgroup
    private Visgroup rootVisgroup = new Visgroup("root", null);

    // camera list
    private List<Camera> cameras = new ArrayList<>();

    private Entity worldspawn;
    private String comment;

    public VmfMeta(BspFileReader reader, VmfWriter writer) {
        super(reader, writer);

        if (bsp.entities.isEmpty())
        {
            L.warning("Couldn't get Worldspawn-entity, because entity list is empty (Probably because map uses external lump files). The map may be missing some information like skybox or detail sprites...");
        } else {
            worldspawn = bsp.entities.get(0);

            // check for existing map comment
            if (worldspawn.getValue("comment") != null) {
                L.log(Level.INFO, "Map comment: {0}", worldspawn.getValue("comment"));
            }
        }
    }

    public Set<Integer> getUIDBlackList() {
        return uidbl;
    }

    /**
     * Returns a new VMF unique ID.
     * 
     * @return UID
     */
    public int getUID() {
        if (uidbl.isEmpty()) {
            return uid++;
        } else {
            // increment ID until it's not found in the blacklist anymore
            do {
                uid++;
            } while (uidbl.contains(uid));

            return uid;
        }
    }

    /**
     * Returns the VMF UID for the corresponding face index.
     * It automatically looks up the original face if the split face wasn't found.
     * 
     * @param iface face index
     * @return brush side ID or -1 if the index isn't mapped yet
     */
    public int getFaceUID(int iface) {
        if (faceUIDs.containsKey(iface)) {
            return faceUIDs.get(iface);
        } else {
            // try origface
            int ioface = bsp.faces.get(iface).origFace;
            if (origFaceUIDs.containsKey(ioface)) {
                return origFaceUIDs.get(ioface);
            }
        }

        // not found
        return -1;
    }

    /**
     * Sets the VMF UID for the given face index.
     * 
     * @param iface face index
     * @param id VMF UID generated by {@link #getUID}
     * @return previously mapped UID or <tt>null</tt> if there was no mapping
     */
    public Integer setFaceUID(int iface, int id) {
        return faceUIDs.put(iface, id);
    }

    /**
     * Sets the VMF UID for the given original face index.
     * 
     * @param iface face index
     * @param id VMF UID generated by {@link #getUID}
     * @return previously mapped UID or <tt>null</tt> if there was no mapping
     */
    public Integer setOrigFaceUID(int iface, int id) {
        return origFaceUIDs.put(iface, id);
    }

    /**
     * Returns the VMF UID for the corresponding dispInfo index.
     * 
     * @param idispinfo dispinfo index
     * @return brush side ID or -1 if the index isn't mapped yet
     */
    public int getDispInfoUID(short idispinfo) {
        if (dispinfoUIDs.containsKey(idispinfo)) {
            return dispinfoUIDs.get(idispinfo);
        }

        // not found
        return -1;
    }

    /**
     * Sets the VMF UID for the given displacement info index.
     * 
     * @param idispinfo dispinfo index
     * @param id VMF UID generated by {@link #getUID}
     * @return previously mapped UID or <tt>null</tt> if there was no mapping
     */
    public Integer setDispInfoUID(short idispinfo, int id) {
        return dispinfoUIDs.put(idispinfo, id);
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getComment() {
        return comment;
    }

    /**
     * Writes the worldspawn header
     */
    public void writeWorldHeader() {
        writer.start("world");
        writer.put("id", getUID());
        if (worldspawn != null)
            writer.put(worldspawn);

        // write comment
        if (comment != null) {
            writer.put("comment", comment);
        }

        writer.put("classname", "worldspawn");
    }

    /**
     * Writes the worldspawn footer
     */
    public void writeWorldFooter() {
        writer.end("world");
    }

    public void writeVisgroups() {
        if (rootVisgroup.visgroups.isEmpty()) {
            return;
        }

        writer.start("visgroups");
        rootVisgroup.visgroups.forEach(this::writeVisgroup);
        writer.end("visgroups");
    }

    private void writeVisgroup(Visgroup visgroup) {
        if (!visgroup.isTreeUsed()) {
            return;
        }

        writer.start("visgroup");

        writer.put("name", visgroup.name);
        writer.put("visgroupid", visgroup.id);
        visgroup.visgroups.forEach(this::writeVisgroup);

        writer.end("visgroup");
    }

    public void writeMetaVisgroup(String visgroupName) {
        writeMetaVisgroups(Collections.singletonList(rootVisgroup.getVisgroup(visgroupName)));
    }

    public void writeMetaVisgroups(List<Visgroup> visgroups) {
        writer.start("editor");
        for (Visgroup vg : visgroups) {
            if (vg == rootVisgroup) {
                throw new IllegalArgumentException("Root visgroup cannot be written");
            }
            if (vg.getRootVisgroup() != rootVisgroup) {
                throw new IllegalArgumentException(String.format(
                        "Visgroup '%s' is not part of this VmfMetas' root visgroup",
                        String.join("/", vg.getVisgroupPath())
                ));
            }

            writer.put("visgroupid", vg.id);
            vg.used = true;
        }
        writer.end("editor");
    }

    /**
     * This method returns the root visgroup, which holds every visgroup
     * that will be written into the final vmf.
     * <p>Child visgroups can be created by calling {@link Visgroup#getVisgroup(String)}
     * on the result object.
     *
     * @return the root visgroup
     * @see Visgroup#getVisgroup(String)
     */
    public Visgroup visgroups() {
        return rootVisgroup;
    }

    /**
     * Returns a new visgroup id for the specified visgroup. This method either just incrementally
     * returns a new integer or, if there exists a reseverd id for this visgroup, it returns this one.
     *
     * @param vg The specific visgroup, for which a new id should be created/retrieved
     * @return a new/resevered integer id for the specified visgroup
     */
    private int getNewVisgroupId(Visgroup vg) {
        return Optional.ofNullable(reservedVisgroups.get(vg.getVisgroupPath()))
                .orElseGet(() -> IntStream.generate(() -> visgroupIndex++)
                        .filter(id -> !reservedVisgroups.containsValue(id))
                        .findFirst()
                        .orElseThrow(RuntimeException::new));
    }

    /**
     * Overloaded method for {@link #reserveVisgroupId(int, List)}
     *
     * @param visgroupId Integer id, the specified visgroup will use in the vmf
     * @param visgroupNames Visgroup path which should use a specific id.
     *                      Each element in this list correspond to a 'node' in the visgroup path.
     *                      The first node is the root node, the second one the child of that node and so on...
     * @throws VisgroupException if the specified id is already taken by a visgroup
     * @see #reserveVisgroupId(int, List)
     */
    public void reserveVisgroupId(int visgroupId, String... visgroupNames) throws VisgroupException {
        reserveVisgroupId(visgroupId, Arrays.asList(visgroupNames));
    }

    /**
     * Reserves the specified id for the specified visgroup.
     * <p>This reserves the specified id to be used for the specified visgroup path.
     *
     * <p><b>WARNING:</b> Any calls to this method must be made <b>before</b> any subsequent calls are made to
     * either {@link #writeMetaVisgroup(String)} or {@link #writeMetaVisgroups(List)}.
     * Else no guarantee can be made that the specified id can be reserved!
     *
     * @param visgroupId Integer id, the specified visgroup will use in the vmf
     * @param visgroupPath Visgroup path which should use a specific id.
     *                     Each element in this list correspond to a 'node' in the visgroup path.
     *                     The first node is the root node, the second one the child of that node and so on...
     * @throws VisgroupException if the specified id is already taken by a visgroup
     */
    public void reserveVisgroupId(int visgroupId, List<String> visgroupPath) throws VisgroupException {
        if (visgroupPath.isEmpty() || visgroupPath.stream().anyMatch(String::isEmpty)) {
            throw new IllegalArgumentException("Invalid visgroup path: " + visgroupPath);
        }

        // We add the 'root' visgroup as first element, because internally we always include it
        // Eg: Visgroup.getVisgroupPath() always has the root visgroup as first element
        List<String> visgroupPathFixed = new ArrayList<>(visgroupPath);
        visgroupPathFixed.add(0, rootVisgroup.name);

        if (rootVisgroup.containsVisgroupId(visgroupId)) {
            throw new VisgroupException(String.format("Tried to reserve already taken visgroup id %d to '%s'",
                    visgroupId,
                    String.join("/", visgroupPathFixed)));
        }

        // Find a duplicate with the same reserved id
        Optional<List<String>> duplicatedVisgroupPath = reservedVisgroups.entrySet().stream()
                .filter(entry -> entry.getValue() == visgroupId)
                .map(Map.Entry::getKey)
                .findAny();

        if (duplicatedVisgroupPath.isPresent()) {
            throw new VisgroupException(String.format(
                    "Tried to reserve visgroup %s with id %d, which is already reserved by %s",
                    String.join("/", visgroupPathFixed),
                    visgroupId,
                    String.join("/", duplicatedVisgroupPath.get())
            ));
        }
        if (reservedVisgroups.containsKey(visgroupPathFixed)) {
            L.warning(String.format(
                    "Visgroup '%s' is already reserved with id %d, overwriting with %d",
                    String.join("/", visgroupPathFixed),
                    reservedVisgroups.get(visgroupPathFixed),
                    visgroupId
            ));
        }

        reservedVisgroups.put(new ArrayList<>(visgroupPathFixed), visgroupId);
    }

    public void writeCameras() {
        writer.start("cameras");

        if (cameras.isEmpty()) {
            writer.put("activecamera", -1);
        } else {
            writer.put("activecamera", 0);

            for (Camera camera : cameras) {
                writer.start("camera");
                writer.put("position", camera.pos, 2);
                writer.put("look", camera.look, 2);
                writer.end("camera");
            }
        }

        writer.end("cameras");
    }

    public List<Camera> getCameras() {
        return cameras;
    }

    /**
     * Class for representing visgroups in a tree like structure
     */
    public class Visgroup
    {
        private final String name;
        private final int id;
        private final Visgroup parent;
        private final SortedSet<Visgroup> visgroups = new TreeSet<>(Comparator.comparing(vg -> vg.name, AlphanumComparator.COMPARATOR));

        private boolean used = false;

        private Visgroup(String name, Visgroup parent) {
            if (name.isEmpty()) {
                throw new IllegalArgumentException("A visgroup cannot have an empty name");
            }

            this.name = name;
            this.parent = parent;

            if (parent != null) {
                parent.visgroups.add(this);
            }

            // Call last, else we might get problems because variables like 'parent' are not set
            this.id = getNewVisgroupId(this);
        }

        /**
         * @param visgroupName The name of the searched for visgroup
         * @return the child visgroup of this object with the specified name.
         */
        public Visgroup getVisgroup(String visgroupName) {
            if (name.isEmpty()) {
                throw new IllegalArgumentException("A visgroup cannot have an empty name");
            }

            return visgroups.stream()
                    .filter(visgroup -> visgroup.name.equals(visgroupName))
                    .findAny()
                    .orElseGet(() -> new Visgroup(visgroupName, this));
        }

        private Visgroup getRootVisgroup() {
            Visgroup root = this;
            while (root.parent != null) {
                root = root.parent;
            }
            return root;
        }

        private List<String> getVisgroupPath() {
            ArrayList<String> path = new ArrayList<>();
            path.add(name);

            Visgroup vg = this;
            while ((vg = vg.parent) != null) {
                path.add(0, vg.name);
            }

            return path;
        }

        /**
         * @return {@code true}, if any visgroup in this tree is flag as used
         * @see #used
         */
        private boolean isTreeUsed() {
            return visgroupStream().anyMatch(vg -> vg.used);
        }

        /**
         * @param id The specified visgroup id to test for
         * @return {@code true}, if any visgroup, with the specified id, exist in this tree
         */
        private boolean containsVisgroupId(int id) {
            return visgroupStream().anyMatch(vg -> vg.id == id);
        }

        /**
         * @return a {@link Stream} of all visgroup objects in this tree
         */
        private Stream<Visgroup> visgroupStream() {
            return Stream.concat(Stream.of(this), visgroups.stream().flatMap(Visgroup::visgroupStream));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Visgroup visgroup = (Visgroup) o;

            return id == visgroup.id;
        }

        @Override
        public int hashCode() {
            return id;
        }
    }

    public static class VisgroupException extends Exception {
        public VisgroupException() {
        }

        public VisgroupException(String message) {
            super(message);
        }

        public VisgroupException(String message, Throwable cause) {
            super(message, cause);
        }

        public VisgroupException(Throwable cause) {
            super(cause);
        }

        public VisgroupException(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
            super(message, cause, enableSuppression, writableStackTrace);
        }
    }
}
