/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package index;

/**
 * Implements the data structure for an index pointer. 
 * 
 * @author Siavash Sheikhizadeh, Bioinformatics chairgroup, Wageningen
 * University, Netherlands
 */
public class IndexPointer {

    public long node_id;
    public int offset;
    public boolean canonical;

    /**
     * The default constructor of the class
     */
    public IndexPointer() {
        node_id = -1L;
        canonical = false;
        offset = -1;
    }

    /**
     * The non-default constructor of the class
     * @param id The id of the node pointer points to
     * @param c Determines if the pointer points to the forward side 
     * @param p The position in the node pointer points to
     * @param n Number of the pointer to the next kmer
     */
    public IndexPointer(long id, boolean c, int p) {
        node_id = id;
        canonical = c;
        offset = p;
    }

    /**
     * Clears the content of the pointer.
     */
    public void reset() {
        node_id = -1L;
        canonical = false;
        offset = -1;
    }
    
    public String ToString(){
        return Long.toString(node_id)+Boolean.toString(canonical)+Integer.toString(offset);
    }

}
