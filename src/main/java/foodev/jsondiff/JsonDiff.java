package foodev.jsondiff;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonParser.Feature;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;

import foodev.jsondiff.incava.IncavaDiff;
import foodev.jsondiff.incava.IncavaEntry;


/**
 * Util for comparing two json-objects and create a new object with a set of instructions to transform the first to the
 * second. The output of this util can be fed into {@link JsonPatch#apply(ObjectNode, ObjectNode)}.
 * 
 * <p>
 * Syntax for instructions:
 * 
 * <pre>
 * <code>
 * {
 *   "key":     "replaced",           // added or replacing key
 *   "~key":    "replaced",           // added or replacing key (~ doesn't matter for primitive data types)
 *   "key":     null,                 // added or replacing key with null.
 *   "~key":    null,                 // added or replacing key with null (~ doesn't matter for null)
 *   "-key":    0                     // key removed (value is ignored)
 *   "key":     { "sub": "replaced" } // whole object "key" replaced
 *   "~key":    { "sub": "merged" }   // key "sub" merged into object "key", rest of object untouched
 *   "key":     [ "replaced" ]        // whole array added/replaced
 *   "~key":    [ "replaced" ]        // whole array added/replaced (~ doesn't matter for whole array)
 *   "key[4]":  { "sub": "replaced" } // object replacing element 4, rest of array untouched
 *   "~key[4]": { "sub": "merged"}    // merging object at element 4, rest of array untouched
 *   "key[+4]": { "sub": "array add"} // object added after 3 becoming the new 4 (current 4 pushed right)
 *   "~key[+4]":{ "sub": "array add"} // object added after 3 becoming the new 4 (current 4 pushed right)
 *   "-key[4]:  0                     // removing element 4 current 5 becoming new 4 (value is ignored)
 * }
 * </code>
 * </pre>
 * 
 * @author Martin Algesten
 * 
 */
public class JsonDiff {

    final static ObjectMapper JSON = new ObjectMapper();


    public static String diff(String from, String to) {
      
        try {
            JsonNode fromEl = JSON.readTree(from);
            JsonNode toEl = JSON.readTree(to);
    
            if (!fromEl.isObject()) {
                throw new IllegalArgumentException("From can't be parsed to json object");
            }
            if (!toEl.isObject()) {
                throw new IllegalArgumentException("To can't be parsed to json object");
            }
    
            return diff((ObjectNode) fromEl, (ObjectNode) toEl).toString();
        } catch (Exception e) {
            throw new RuntimeException("Unable to diff JSON strings.", e);
        }
            
    }


    public static ObjectNode diff(ObjectNode from, ObjectNode to) {

        Root fromRoot = new Root();
        Root toRoot = new Root();

        ArrayList<Leaf> fromLeaves = new ArrayList<Leaf>();
        ArrayList<Leaf> toLeaves = new ArrayList<Leaf>();

        findLeaves(fromRoot, from, fromLeaves);
        findLeaves(toRoot, to, toLeaves);

        IncavaDiff<Leaf> idiff = new IncavaDiff<Leaf>(fromLeaves, toLeaves);

        List<IncavaEntry> ipatch = idiff.diff();

        ObjectNode patch = JSON.createObjectNode();

        buildPatch(patch, ipatch, fromLeaves, toLeaves);

        return patch;

    }


    private static void buildPatch(ObjectNode patch, List<IncavaEntry> diff,
            ArrayList<Leaf> from, ArrayList<Leaf> to) {

        // quick lookups to check whether a key/index has been added or deleted
        HashSet<Integer> deleted = new HashSet<Integer>();
        HashSet<Integer> added = new HashSet<Integer>();

        for (IncavaEntry d : diff) {
            for (int i = d.getDeletedStart(), n = d.getDeletedEnd(); i <= n; i++) {
                deleted.add(from.get(i).parent.doHash(true));
            }
            for (int i = d.getAddedStart(), n = d.getAddedEnd(); i <= n; i++) {
                added.add(to.get(i).parent.doHash(true));
            }
        }

        for (IncavaEntry d : diff) {

            if (d.getDeletedEnd() >= 0) {

                int i = d.getDeletedStart();

                Leaf prev = from.get(i);

                // if something is in added, it's a change, we don't
                // do delete + add for change, just add
                if (!added.contains(prev.parent.doHash(true))) {

                    // not array, just remove
                    addInstruction(patch, prev, true, false);

                }

                for (i = i + 1; i <= d.getDeletedEnd(); i++) {

                    Leaf cur = from.get(i);

                    if (cur.hasAncestor(prev.parent)) {

                        // ignore since the whole parent is deleted/changed.

                    } else if (!added.contains(cur.parent.doHash(true))) {

                        // add remove instruction
                        addInstruction(patch, cur, true, false);

                        prev = cur;

                    }

                }

            }

            if (d.getAddedEnd() >= 0) {

                int i = d.getAddedStart();

                Leaf prev = to.get(i);
                addInstruction(patch, prev, deleted.contains(prev.parent.doHash(true)), true);

                for (i = i + 1; i <= d.getAddedEnd(); i++) {

                    Leaf cur = to.get(i);

                    if (cur.hasAncestor(prev.parent)) {
                        // ignore since the whole parent has been added.
                    } else {
                        addInstruction(patch, cur, deleted.contains(cur.parent.doHash(true)), true);
                        prev = cur;
                    }

                }

            }

        }

    }


    private static void addInstruction(ObjectNode patch, Leaf leaf, boolean isInDeleted, boolean isInAdded) {

        ArrayList<Node> path = leaf.toPath();
        ObjectNode cur = patch;
        StringBuilder keyBld = new StringBuilder();

        int last = path.size() - 1;

        for (int i = 0; i < last; i++) {

            buildKey(keyBld, path, i);
            keyBld.insert(0, '~');

            String key = keyBld.toString();

            ObjectNode tmp;
            if (cur.has(key)) {
                tmp = (ObjectNode) cur.get(key);
            } else {
                tmp = JSON.createObjectNode();
                cur.put(key, tmp);
            }

            cur = tmp;

        }

        buildKey(keyBld, path, last);

        if (isInAdded) {

            if (!isInDeleted) {

                ObjNode o = (ObjNode) path.get(last);

                if (o.subindex != null) {

                    // not in deleted and has an array specifier,
                    // it's an inserted array member
                    int p = keyBld.lastIndexOf("[");
                    keyBld.insert(p + 1, '+');

                }

            }

            cur.put(keyBld.toString(), leaf.val);


        } else if (isInDeleted) {

            keyBld.insert(0, '-');
            cur.put(keyBld.toString(), 0);

        }

    }


    private static void buildKey(StringBuilder key, ArrayList<Node> path, int i) {

        key.delete(0, key.length());

        ObjNode o = (ObjNode) path.get(i);

        key.append(o.key);

        ArrNode a = o.subindex;

        while (a != null) {
            key.append('[');
            key.append(a.index);
            key.append(']');
            a = a.subindex;
        }

    }


    private static void findLeaves(Node parent, JsonNode el, List<Leaf> leaves) {

        leaves.add(new Leaf(parent, el));

        if (el.isObject()) {

            for (Iterator<Entry<String, JsonNode>> i = el.getFields(); i.hasNext(); ) {
                Entry<String, JsonNode> e = i.next();
                ObjNode newParent = new ObjNode(parent, e.getKey());
                findLeaves(newParent, e.getValue(), leaves);

            }

        } else if (el.isArray()) {

            ArrayNode arr = (ArrayNode) el;

            for (int i = 0, n = arr.size(); i < n; i++) {

                ArrNode newParent = new ArrNode(parent, i);
                findLeaves(newParent, arr.get(i), leaves);

            }

        }


    }


    private static Comparator<Entry<String, JsonNode>> ENTRY_COMPARATOR = new Comparator<Entry<String, JsonNode>>() {

        @Override
        public int compare(Entry<String, JsonNode> o1, Entry<String, JsonNode> o2) {

            return o1.getKey().compareTo(o2.getKey());

        }
    };


    private static class Leaf implements Comparable<Leaf> {

        Node parent;
        JsonNode val;
        Integer hash;
        ArrayList<Node> path;


        Leaf(Node parent, JsonNode val) {
            this.parent = parent;
            this.val = val;
        }


        public ArrayList<Node> toPath() {
            if (path != null) {
                return path;
            }

            ArrayList<Node> tmp = new ArrayList<Node>();
            Node cur = parent;

            tmp.add(cur);
            while (!(cur.parent instanceof Root)) {
                cur = cur.parent;
                tmp.add(cur);
            }
            Collections.reverse(tmp);

            for (int i = 0; i < tmp.size(); i++) {

                ObjNode o = (ObjNode) tmp.get(i);

                int j = i + 1;
                if (j < tmp.size() && tmp.get(j) instanceof ArrNode) {
                    o.subindex = (ArrNode) tmp.get(j);
                    ArrNode a = o.subindex;
                    tmp.remove(j);
                    while (j < tmp.size() && tmp.get(j) instanceof ArrNode) {
                        a.subindex = (ArrNode) tmp.get(j);
                        a = a.subindex;
                        tmp.remove(j);
                    }
                }

            }

            return tmp;

        }


        public boolean hasAncestor(Node anc) {
            return parent == anc || parent.hasAncestor(anc);
        }


        @Override
        public int hashCode() {
            if (hash != null) {
                return hash;
            }
            int i = parent.hashCode();
            i = i * 31 + (val.isValueNode() || val.isNull() ? val.hashCode() : 0);
            hash = i;
            return hash;
        }


        @Override
        public boolean equals(Object obj) {
            return hashCode() == ((Leaf) obj).hashCode();
        }


        @Override
        public int compareTo(Leaf o) {
            return hashCode() - o.hashCode();
        }


        @Override
        public String toString() {
            return toPath().toString() + " " + hashCode();
        }

    }


    private static abstract class Node {

        Node parent;
        int hash = -1;


        Node(Node parent) {
            this.parent = parent;
        }


        public boolean hasAncestor(Node anc) {
            return parent == anc || parent.hasAncestor(anc);
        }


        @Override
        public int hashCode() {
            if (hash >= 0) {
                return hash;
            }
            hash = doHash(false);
            return hash;
        }


        protected abstract int doHash(boolean indexed);

    }


    private static class ObjNode extends Node {

        String key;

        ArrNode subindex;


        ObjNode(Node parent, String key) {
            super(parent);
            this.key = key;
        }


        @Override
        protected int doHash(boolean indexed) {
            int i = parent.hashCode();
            i = i * 31 + key.hashCode();
            return i;
        }


        @Override
        public String toString() {
            return key;
        }

    }


    private static class ArrNode extends Node {

        int index;

        ArrNode subindex;


        ArrNode(Node parent, int index) {
            super(parent);
            this.index = index;
        }


        @Override
        protected int doHash(boolean indexed) {
            int i = parent.hashCode();
            i = i * 31 + ArrNode.class.hashCode();
            if (indexed) {
                i = i * 31 + index;
            }
            return i;
        }


        @Override
        public String toString() {
            return "" + index;
        }

    }


    private static class Root extends Node {


        Root() {
            super(null);
        }


        @Override
        protected int doHash(boolean indexed) {
            return 0;
        }


        @Override
        public boolean hasAncestor(Node anc) {
            return false;
        }


        @Override
        public String toString() {
            return "root";
        }

    }

}
