/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package pangenome;

import alignment.ProteinAlignment;
import alignment.SequenceAlignment;
import genome.SequenceDatabase;
import index.IndexPointer;
import protein.protein_builder;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.PriorityQueue;
import java.util.Queue;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.ResourceIterator;
import org.neo4j.graphdb.Transaction;
import org.neo4j.graphdb.factory.GraphDatabaseFactory;
import static pangenome.SequenceLayer.append_fwd;
import static pangenome.SequenceLayer.append_rev;

import static pantools.Pantools.GENOME_DATABASE_PATH;
import static pantools.Pantools.GRAPH_DATABASE_PATH;
import static pantools.Pantools.K;
import static pantools.Pantools.RelTypes;
import static pantools.Pantools.gene_label;
import static pantools.Pantools.genomeDb;
import static pantools.Pantools.graphDb;
import static pantools.Pantools.RNA_label;
import static pantools.Pantools.num_edges;
import static pantools.Pantools.num_nodes;
import static pantools.Pantools.pangenome_label;
import static pantools.Pantools.sequence_label;
import static pantools.Pantools.startTime;
import static pangenome.SequenceLayer.locate;
import static pangenome.SequenceLayer.get_outgoing_edge;
import static pantools.Pantools.CDS_label;
import static pantools.Pantools.MAX_TRANSACTION_SIZE;
import static pantools.Pantools.broken_protein_label;
import static pantools.Pantools.coding_gene_label;
import static pantools.Pantools.coding_group_lable;
import static pantools.Pantools.noncoding_group_lable;
import static pantools.Pantools.reverse_complement;
import static pantools.Pantools.super_group_lable;
import static pantools.Pantools.write_fasta;

/**
 * Implements all the functionalities related to the annotation layer of the pangenome
 * 
 * @author Siavash Sheikhizadeh, Bioinformatics chairgroup, Wageningen
 * University, Netherlands
 */
public class AnnotationLayer {
    public double LEN_FACTOR = 0.5;
    public double THRESHOLD = 0.75;

    /**
     * Implements a comparator for integer arrays of size two
     */
    public class PairComparator implements Comparator<int[]> {
        @Override
        public int compare(int[] x, int[] y) {
            if (x[2] < y[2]) 
                return -1;
            else if (x[2] > y[2]) 
                return 1;
            else if (x[3] < y[3]) 
                return -1;
            else if (x[3] > y[3]) 
                return 1;
            else
                return 0;
        }
    }

    /**
     * Adds nodes of different genomic features to the pangenome.
     * All features should be annotated in the hierarchical order of gene, RNA, exon, CDS  
     * 
     * @param gff_paths_file A text file listing the paths to the annotation files
     * @param PATH Path to the database folder
     */
    public void annotate(String gff_paths_file, String PATH) {
        int i, j, num_genes, num_mRNAs, num_tRNAs, num_rRNAs, protein_num = 0, total_genes=0;
        int isoforms_num, gene_start_pos = 0, feature_len;
        String sequence_id, current_sequence_id=null, attribute;
        Node db_node, gene_node = null, rna_node = null, cds_node;
        protein_builder pb = new protein_builder();
        StringBuilder log = new StringBuilder();
        StringBuilder coding_RNA = new StringBuilder();
        String[] fields,parent_ids;
        StringBuilder gene_builder = new StringBuilder();
        String strand, gff_name, line=null, protein, ID;
        List<Long>[][] genes_list;
        int[] address = new int[4];
        long[] genes_array;
        int[] begin_end;
        PairComparator comp = new PairComparator();
        PriorityQueue<int[]> pq = new PriorityQueue(comp);
        if (new File(PATH + GRAPH_DATABASE_PATH).exists()) {
            graphDb = new GraphDatabaseFactory().newEmbeddedDatabaseBuilder(new File(PATH + GRAPH_DATABASE_PATH))
                    .setConfig("keep_logical_logs", "100M size").newGraphDatabase();
            registerShutdownHook(graphDb);
            try (Transaction tx1 = graphDb.beginTx()) {
                db_node = graphDb.findNodes(pangenome_label).next();
                if (db_node == null) {
                    System.out.println("Can not locate database node!");
                    System.exit(1);
                }
                // Read the pangenome information    
                K = (int) db_node.getProperty("k_mer_size");
                num_nodes = (int) db_node.getProperty("num_nodes");
                num_edges = (int) db_node.getProperty("num_edges");
                tx1.success();
            }
            startTime = System.currentTimeMillis();
            genomeDb = new SequenceDatabase(PATH + GENOME_DATABASE_PATH);
            genes_list = new ArrayList[genomeDb.num_genomes + 1][];
            for (i = 1; i <= genomeDb.num_genomes; ++i) {
                genes_list[i] = new ArrayList[genomeDb.num_sequences[i] + 1];
                for (j = 1; j <= genomeDb.num_sequences[i]; ++j) {
                    genes_list[i][j] = new ArrayList();
                }
            }
            try (BufferedReader gff_files = new BufferedReader(new FileReader(gff_paths_file))) {
                System.out.println("genome\tgenes\tmRNAs\ttRNAs\trRNAs");
                for (address[0] = 1; gff_files.ready() && address[0] <= genomeDb.num_genomes; ++address[0]) // for each gff file
                {
                    gene_node = null;
                    num_genes = num_mRNAs = num_tRNAs = num_rRNAs = 0;
                    gff_name = gff_files.readLine();
                    if (gff_name.equals(""))
                        continue;
                    BufferedReader in = new BufferedReader(new FileReader(gff_name));
                    BufferedWriter out = new BufferedWriter(new FileWriter(gff_name + ".proteins.fasta"));
                // for each record of gff file
                    while (in.ready()) 
                    {
                        try (Transaction tx2 = graphDb.beginTx()) {
                            for (i = 0; i < MAX_TRANSACTION_SIZE/100 && in.ready(); ++i) {
                                line = in.readLine();
                                if (line.equals("") || line.charAt(0) == '#') // if line is empty or a comment skip it
                                {
                                    continue;
                                }
                                fields = line.split("\\t");
                                if (fields.length < 8) {
                                    continue;
                                }
                                address[2] = Integer.parseInt(fields[3]); // begin
                                address[3] = Integer.parseInt(fields[4]); // end
                                strand = fields[6];
                                feature_len = address[3] - address[2] + 1;
                                attribute = fields[fields.length - 1];
                                sequence_id = fields[0]; // sequence id
                            // if a feature belongs to a new sequence 
                            // find the sequence number according to the sequence id in the gff file                                 
                                if ( ! sequence_id.equals(current_sequence_id) ) {
                                   address[1] = find_sequence(sequence_id, address[0]);
                                   current_sequence_id = sequence_id;
                                }
                            // if sequence found    
                                if (address[1] > 0) 
                                {
                                    if (fields[2].endsWith("gene")) {
                                        // for the previous gene node. Translate the proteins of the gene.
                                            if (gene_node != null){ 
                                                isoforms_num =0;
                                                for (Relationship r1: gene_node.getRelationships(RelTypes.codes_for, Direction.OUTGOING)) {
                                                    ++isoforms_num;
                                                    rna_node = r1.getEndNode();
                                                    if (rna_node.hasRelationship(RelTypes.contributes_to, Direction.INCOMING)){
                                                        gene_node.addLabel(coding_gene_label);
                                                        for (Relationship r2: rna_node.getRelationships(RelTypes.contributes_to, Direction.INCOMING)) {
                                                            cds_node = r2.getStartNode();
                                                            pq.add((int[])cds_node.getProperty("address"));
                                                        }
                                                        for (coding_RNA.setLength(0);!pq.isEmpty();) {
                                                            begin_end = pq.remove();
                                                            coding_RNA.append(gene_builder.substring(begin_end[2] - gene_start_pos, begin_end[3] - gene_start_pos + 1));
                                                        }
                                                        protein = pb.translate(gene_node.getProperty("strand").equals("+")?coding_RNA.toString():reverse_complement(coding_RNA.toString()));
                                                        if (protein.startsWith("M") && protein.endsWith("*")){
                                                            ++protein_num;
                                                            out.write(">" + protein_num + "\n");
                                                            write_fasta(out, protein, 70);
                                                            rna_node.setProperty("protein", protein);
                                                            rna_node.setProperty("protein_number", protein_num);
                                                            rna_node.setProperty("protein_length", protein.length());
                                                        } else
                                                           rna_node.addLabel(broken_protein_label);
                                                    }
                                                }
                                                gene_node.setProperty("isoforms_num", isoforms_num);
                                            }
                                            gene_start_pos = address[2];
                                        // create new gene node    
                                            gene_node = graphDb.createNode(gene_label);
                                            gene_node.setProperty("address", address);
                                            gene_node.setProperty("strand", strand);
                                            gene_node.setProperty("length", feature_len);
                                            gene_node.setProperty("ID", get_property(attribute,"ID"));
                                            gene_node.setProperty("fields", fields);
                                            gene_node.setProperty("type", fields[2]);
                                        // extract gene sequence as appears in the sequence and connects it to its nodes   
                                            gene_builder.setLength(0);
                                            connect_gene_to_nodes(gene_builder, address, gene_node);
                                        // adding gene_node id to the sequence node
                                            genes_list[address[0]][address[1]].add(gene_node.getId());
                                            ++total_genes;
                                            ++num_genes;
                                    } else if(fields[2].endsWith("RNA") || fields[2].equals("pseudogenic_transcript")) {
                                        rna_node = graphDb.createNode(RNA_label);
                                        rna_node.setProperty("ID", get_property(attribute,"ID"));
                                        rna_node.setProperty("address", address);
                                        rna_node.setProperty("length", feature_len);
                                        rna_node.setProperty("type", fields[2]);
                                        gene_node.createRelationshipTo(rna_node, RelTypes.codes_for);
                                        switch (fields[2]){
                                            case "mRNA":
                                                ++num_mRNAs;
                                                break;
                                            case "tRNA":
                                                ++num_tRNAs;
                                                rna_node.setProperty("sequence", strand.equals("+")?gene_builder.toString():reverse_complement(gene_builder.toString()));
                                                break;
                                            case "rRNA":
                                                ++num_rRNAs;
                                                rna_node.setProperty("sequence", strand.equals("+")?gene_builder.toString():reverse_complement(gene_builder.toString()));
                                                break;
                                        }
                                    } else if (fields[2].equals("CDS")) {
                                            cds_node = graphDb.createNode(CDS_label);
                                            ID = get_property(attribute,"ID");
                                            cds_node.setProperty("ID", ID);
                                            cds_node.setProperty("address", address);
                                            parent_ids = get_property(attribute,"Parent").split(",");
                                        // connect CDS to its parent RNA    
                                            for (i=0;i<parent_ids.length;++i)
                                                for (Relationship r: gene_node.getRelationships(RelTypes.codes_for, Direction.OUTGOING)) 
                                                {
                                                    rna_node = r.getEndNode();
                                                    if ( parent_ids[i].equals(rna_node.getProperty("ID")) ){
                                                        cds_node.createRelationshipTo(rna_node, RelTypes.contributes_to);
                                                    }
                                                }
                                    }
                                } else // if sequence not found
                                {
                                    log.append(sequence_id).append(" missed in genome ").append(address[0]).append("\n"); // usually organal genes
                                }
                                if (i % 500 == 1)
                                    System.out.print("\r" + address[0] + "\t" + num_genes + "\t" + num_mRNAs + "\t" + num_tRNAs + "\t" + num_rRNAs + "\t");
                            }// for trsc
                            tx2.success();
                        } // tx2
                    } // while lines
                // for the last gene in the file. Translate the proteins of the gene.
                    try (Transaction tx = graphDb.beginTx()) {
                        if (gene_node != null){ 
                            isoforms_num =0;
                            for (Relationship r1: gene_node.getRelationships(RelTypes.codes_for, Direction.OUTGOING)) {
                                ++isoforms_num;
                                rna_node = r1.getEndNode();
                                if (rna_node.hasRelationship(RelTypes.contributes_to, Direction.INCOMING)){
                                    gene_node.addLabel(coding_gene_label);
                                    for (Relationship r2: rna_node.getRelationships(RelTypes.contributes_to, Direction.INCOMING)) {
                                        cds_node = r2.getStartNode();
                                        pq.add((int[])cds_node.getProperty("address"));
                                    }
                                    for (coding_RNA.setLength(0);!pq.isEmpty();) {
                                        begin_end = pq.remove();
                                        coding_RNA.append(gene_builder.substring(begin_end[2] - gene_start_pos, begin_end[3] - gene_start_pos + 1));
                                    }
                                    protein = pb.translate(gene_node.getProperty("strand").equals("+")?coding_RNA.toString():reverse_complement(coding_RNA.toString()));
                                    if (protein.startsWith("M") && protein.endsWith("*")){
                                        ++protein_num;
                                        out.write(">" + protein_num + "\n");
                                        write_fasta(out, protein, 70);
                                        rna_node.setProperty("protein", protein);
                                        rna_node.setProperty("protein_number", protein_num);
                                        rna_node.setProperty("protein_length", protein.length());
                                    } else
                                        rna_node.addLabel(broken_protein_label);
                                }
                            }
                            gene_node.setProperty("isoforms_num", isoforms_num);
                        }
                        tx.success();
                    }
                    in.close();
                    out.close();
                    System.out.println("\r" + address[0] + "\t" + num_genes + "\t" + num_mRNAs + "\t" + num_tRNAs + "\t" + num_rRNAs + "\t");
                } // for genomes
                gff_files.close();
            } catch (IOException ioe) {
                System.out.println(ioe.getMessage());
                System.exit(1);
            }
            // setting the genes property of sequence nodes    
            try (Transaction tx4 = graphDb.beginTx()) {
                for (i = 1; i <= genomeDb.num_genomes; ++i) {
                    for (j = 1; j <= genomeDb.num_sequences[i]; ++j) {
                        genes_array = new long[genes_list[i][j].size()];
                        Iterator<Long> genesIterator = genes_list[i][j].iterator();
                        for (int k = 0; genesIterator.hasNext(); ++k) {
                            genes_array[k] = genesIterator.next();
                        }
                        graphDb.findNode(sequence_label, "number", i + "_" + j).setProperty("genes", genes_array);
                    }
                }
                db_node.setProperty("num_genes",total_genes);
                tx4.success();
            }
            try {
                BufferedWriter out = new BufferedWriter(new FileWriter(PATH + "/annotation.log"));
                out.write(log.toString());
                out.close();
            } catch (IOException ioe) {
            }
            graphDb.shutdown();
            genomeDb.close();
            // delete the database transaction files
            File directory = new File(PATH + GRAPH_DATABASE_PATH);
            for (File f : directory.listFiles())
                if (f.getName().startsWith("neostore.transaction.db."))
                    f.delete();
        } else {
            System.out.println("No database found in " + PATH);
            System.exit(1);
        }
    }
    
    /**
     * Extracts the value of given property from the attribute of the feature 
     * @param attribute Attribute of the feature 
     * @param property Property name
     * @return 
     */
    private String get_property(String attribute, String property){
        String[] fields = attribute.split(";");
        for (int i=0;i<fields.length;++i)
            if (fields[i].startsWith(property))
                return fields[i].split("=")[1];
        return "null";
    }
    
    /**
     * Finds the number of a sequence in a genome given its name.
     * 
     * @param name Name of the sequence
     * @param genome Number of the genome
     * @return The number of the sequence in the genome
     */
    private int find_sequence(String name, int genome) {
        boolean found = false;
        int sequence;
        for (found = false, sequence = 1; !found && sequence <= genomeDb.num_sequences[genome]; ++sequence) {
            if (genomeDb.sequence_titles[genome][sequence].contains(name)) {
                found = true;
            }
        }
        --sequence;
        if (found) {
            return sequence;
        } else {
            return -1;
        }
    }

    /**
     * Connect the annotated gene to all the nodes its sequence passes through in the pangenome.
     * At the same time assembles the sequence of the gene
     * 
     * @param gene_builder String builder which will contain the sequence of the gene  
     * @param address An array containing {genome, sequence, begin, end}
     * @param gene_node The gene node itself
     */
    public void connect_gene_to_nodes(StringBuilder gene_builder, int[] address, Node gene_node) {
        Relationship rel;
        int loc, node_len, neighbor_len, seq_len = address[3] - address[2] + 1, starts_at;
        String rel_name;
        gene_builder.setLength(0);
        loc = address[2]-1;
        IndexPointer start_ptr = locate(address);
        starts_at = start_ptr.position;
        Node neighbor, node = graphDb.getNodeById(start_ptr.node_id);
        node_len = (int) node.getProperty("length");
        rel = gene_node.createRelationshipTo(node, RelTypes.visits);
        rel.setProperty("position", loc);
        rel.setProperty("forward", start_ptr.canonical);
        rel.setProperty("starts_at", starts_at);
        gene_node.setProperty("start_node_id", node.getId());
        gene_node.setProperty("start_edge_id", rel.getId());
        if (start_ptr.canonical) {
            if (starts_at + seq_len - 1 <= node_len - 1) {
                loc += append_fwd(gene_builder, (String) node.getProperty("sequence"), starts_at, starts_at + seq_len - 1);
            } else {
                loc += append_fwd(gene_builder, (String) node.getProperty("sequence"), starts_at, node_len - 1);
            }
        } else {
            if (starts_at - (seq_len - 1) >= 0) {
                loc += append_rev(gene_builder, (String) node.getProperty("sequence"), starts_at - (seq_len - 1), starts_at);
            } else {
                loc += append_rev(gene_builder, (String) node.getProperty("sequence"), 0, starts_at);
            }
        }
        while (gene_builder.length() < seq_len ) {
            address[2] = loc - K + 1;
            rel = get_outgoing_edge(node, address);
            neighbor = rel.getEndNode();
            rel_name = rel.getType().name();
            neighbor_len = (int) neighbor.getProperty("length");
            if (rel_name.charAt(1) == 'F')
                if (gene_builder.length() + neighbor_len - K + 1 > seq_len) 
                    loc += append_fwd(gene_builder, (String) neighbor.getProperty("sequence"), K - 1, seq_len - gene_builder.length() + K - 2);
                else 
                    loc += append_fwd(gene_builder, (String) neighbor.getProperty("sequence"), K - 1, neighbor_len - 1);
            else if (gene_builder.length() + neighbor_len - K + 1 > seq_len) 
                loc += append_rev(gene_builder, (String) neighbor.getProperty("sequence"), neighbor_len - K - (seq_len - gene_builder.length()) + 1, neighbor_len - K);
            else 
                loc += append_rev(gene_builder, (String) neighbor.getProperty("sequence"), 0, neighbor_len - K);
            node = neighbor;
            gene_node.createRelationshipTo(node, RelTypes.visits).setProperty("position", loc);;
        } // while
        gene_node.setProperty("stop_node", node.getId());
    }

    /**
     * Groups the highly similar genes together
     */
    public void group_homologs(String[] args) {
        int num_coding_groups=0, num_noncoding_groups=0, total_genes=0, group_size, num_genes,trsc;
        Node group_node, gene_node, current_gene_node = null;
        ResourceIterator<Node> genes_iterator;
        String PATH = args[1];
        if (args.length > 2){
            THRESHOLD = Double.parseDouble(args[2]);
            if (args.length > 3)
                LEN_FACTOR = Double.parseDouble(args[3]);
        }
        if (new File(PATH + GRAPH_DATABASE_PATH).exists()) {
            graphDb = new GraphDatabaseFactory().newEmbeddedDatabaseBuilder(new File(PATH + GRAPH_DATABASE_PATH))
                    .setConfig("keep_logical_logs", "100M size").newGraphDatabase();
            registerShutdownHook(graphDb);
            startTime = System.currentTimeMillis();
            genomeDb = new SequenceDatabase(PATH + GENOME_DATABASE_PATH);
            Queue<Node> gene_nodes = new LinkedList(); 
            //Queue<Node> queue = new LinkedList();
            PriorityQueue<Long> pq = new PriorityQueue();
            SequenceAlignment seq_aligner = new SequenceAlignment(-2.0,-1.0,4.0,-2.0,1000);
            ProteinAlignment pro_aligner = new ProteinAlignment(-1.0,0.25,1000);
            try (Transaction tx1 = graphDb.beginTx()) {
                num_genes = (int)graphDb.findNodes(pangenome_label).next().getProperty("num_genes");
                genes_iterator = graphDb.findNodes(gene_label);
                System.out.println("THRESHOLD="+THRESHOLD+"\tLEN_FACTOR="+LEN_FACTOR);
                System.out.println("Grouping " + num_genes +" genes...");
                System.out.println("genes\t\tcoding_groups\t\tnoncoding_groups");
                while (genes_iterator.hasNext()) {
                    try (Transaction tx2 = graphDb.beginTx()) {
                        for (trsc = 0; genes_iterator.hasNext() && trsc < MAX_TRANSACTION_SIZE; ++trsc) {
                            gene_node=genes_iterator.next();
                        // To avoid having one gene in different groups    
                            if (!gene_node.hasRelationship(RelTypes.contains, Direction.INCOMING)) { 
                                if (gene_node.hasLabel(coding_gene_label) )
                                    group_node = get_homologs(gene_nodes, pq, gene_node, pro_aligner);
                                else 
                                    group_node = get_gene_family(gene_nodes, pq, gene_node, seq_aligner);
                                group_size = gene_nodes.size()+1;
                                if (group_node == null){
                                    if (group_size == 1){
                                        if (gene_node.hasLabel(coding_gene_label)){
                                            group_node = graphDb.createNode(coding_group_lable);
                                            ++num_coding_groups;
                                        } else {
                                            group_node = graphDb.createNode(noncoding_group_lable);
                                            ++num_noncoding_groups;
                                        }
                                        total_genes += 1;
                                        group_node.createRelationshipTo(gene_node, RelTypes.contains);
                                        continue;
                                    }
                                    if (gene_node.hasLabel(coding_gene_label)){
                                        group_node = graphDb.createNode(coding_group_lable);
                                        ++num_coding_groups;
                                    } else {
                                        group_node = graphDb.createNode(noncoding_group_lable);
                                        ++num_noncoding_groups;
                                    }
                                }
                                total_genes += group_size;
                            // Because the gene has not been added to the group itself    
                                group_node.createRelationshipTo(gene_node, RelTypes.contains);
                                while (!gene_nodes.isEmpty()) {
                                    current_gene_node = gene_nodes.remove();
                                    group_node.createRelationshipTo(current_gene_node, RelTypes.contains);
                                }
                                if (num_coding_groups % 10 == 1 )
                                    break;
                            }
                        }// for
                        System.out.print("\r" + total_genes + "\t\t" + num_coding_groups + "\t\t" + num_noncoding_groups);
                        tx2.success();
                    }// transaction 2
                } // while 
                tx1.success();
                System.out.println("\r" + total_genes + "\t\t" + num_coding_groups + "\t\t" + num_noncoding_groups);
                finalize_groups();
                drop_edges_of_type(RelTypes.resembles);
            } // transaction 1
        } else {
            System.out.println("pangenome database not found!");
            System.exit(0);
        }
    }

    public void cnv_report(String PATH, String keyword) {
        /*int num_coding_groups=0, num_noncoding_groups=0, total_genes=0, group_size, num_genes,trsc;
        Node group_node, current_gene_node = null;
        ResourceIterator<Node> groups_iterator;
        if (new File(PATH + GRAPH_DATABASE_PATH).exists()) {
            graphDb = new GraphDatabaseFactory().newEmbeddedDatabaseBuilder(new File(PATH + GRAPH_DATABASE_PATH))
                    .setConfig("keep_logical_logs", "100M size").newGraphDatabase();
            registerShutdownHook(graphDb);
            startTime = System.currentTimeMillis();
            genomeDb = new SequenceDatabase(PATH + GENOME_DATABASE_PATH);
            Queue<Node> gene_nodes = new LinkedList(); 
            PriorityQueue<Long> pq = new PriorityQueue();
            SequenceAlignment seq_aligner = new SequenceAlignment(-2.0,-1.0,4.0,-2.0,1000);
            ProteinAlignment pro_aligner = new ProteinAlignment(-1.0,0.25,1000);
            try (Transaction tx1 = graphDb.beginTx()) {
                groups_iterator = graphDb.findNodes(coding_group_lable);
                System.out.println("THRESHOLD="+THRESHOLD+"\tLEN_FACTOR="+LEN_FACTOR);
                System.out.println("Grouping " + num_genes +" genes...");
                System.out.println("genes\t\tcoding_groups\t\tnoncoding_groups");
                while (groups_iterator.hasNext()) {
                    try (Transaction tx2 = graphDb.beginTx()) {
                        for (trsc = 0; groups_iterator.hasNext() && trsc < MAX_TRANSACTION_SIZE; ++trsc) {
                            group_node=groups_iterator.next();
                        // To avoid having one gene in different groups    
                            if (!gene_node.hasRelationship(RelTypes.contains, Direction.INCOMING)) { 
                                if (gene_node.hasLabel(coding_gene_label) )
                                    group_node = get_homologs(gene_nodes, pq, gene_node, pro_aligner);
                                else 
                                    group_node = get_gene_family(gene_nodes, pq, gene_node, seq_aligner);
                                group_size = gene_nodes.size()+1;
                                if (group_node == null){
                                    if (group_size == 1){
                                        if (gene_node.hasLabel(coding_gene_label)){
                                            group_node = graphDb.createNode(coding_group_lable);
                                            ++num_coding_groups;
                                        } else {
                                            group_node = graphDb.createNode(noncoding_group_lable);
                                            ++num_noncoding_groups;
                                        }
                                        total_genes += 1;
                                        group_node.createRelationshipTo(gene_node, RelTypes.contains);
                                        continue;
                                    }
                                    if (gene_node.hasLabel(coding_gene_label)){
                                        group_node = graphDb.createNode(coding_group_lable);
                                        ++num_coding_groups;
                                    } else {
                                        group_node = graphDb.createNode(noncoding_group_lable);
                                        ++num_noncoding_groups;
                                    }
                                }
                                total_genes += group_size;
                            // Because the gene has not been added to the group itself    
                                group_node.createRelationshipTo(gene_node, RelTypes.contains);
                                while (!gene_nodes.isEmpty()) {
                                    current_gene_node = gene_nodes.remove();
                                    group_node.createRelationshipTo(current_gene_node, RelTypes.contains);
                                }
                                if (num_coding_groups % 10 == 1 )
                                    break;
                            }
                        }// for
                        System.out.print("\r" + total_genes + "\t\t" + num_coding_groups + "\t\t" + num_noncoding_groups);
                        tx2.success();
                    }// transaction 2
                } // while 
                tx1.success();
                System.out.println("\r" + total_genes + "\t\t" + num_coding_groups + "\t\t" + num_noncoding_groups);
                finalize_groups();
                drop_edges_of_type(RelTypes.resembles);
            } // transaction 1
        } else {
            System.out.println("pangenome database not found!");
            System.exit(0);
        }*/
    }
    

    /**
     * 
     * @param gene_nodes
     * @param pq
     * @param gene_node
     * @param pro_aligner 
     */
    private Node get_homologs(Queue<Node> gene_nodes, PriorityQueue<Long> pq, Node gene_node, ProteinAlignment pro_aligner){
        int mRNA_len1, mRNA_len2;
        double similarity;
        Node mRNA_node1, mRNA_node2, current_gene_node,node;
        Relationship r = null;
        long gene_id, current_gene_id;
        boolean found, are_connected;
        String p1, p2;
        pq.clear();
        for (Relationship r1: gene_node.getRelationships(Direction.OUTGOING,RelTypes.visits)) {
            node = r1.getEndNode();
            for (Relationship r2: node.getRelationships(Direction.INCOMING,RelTypes.visits)) {
                current_gene_node = r2.getStartNode();
            // avoid comparisons to the gene itself  
                if(!current_gene_node.equals(gene_node))  
                    pq.offer(current_gene_node.getId());
            }
        }
        if (!pq.isEmpty())
        {
            gene_id = -1l;
        // for all the candidates with some shared node with the gene    
            while (!pq.isEmpty()) { 
                current_gene_id = pq.remove();
            // to skip the repetetive candidate mate genes    
                if(gene_id != current_gene_id){ 
                    current_gene_node = graphDb.getNodeById(current_gene_id);
                    found = false;
                    for (Relationship r1: gene_node.getRelationships(Direction.OUTGOING,RelTypes.codes_for)) {
                        mRNA_node1 = r1.getEndNode();
                        if ( mRNA_node1.hasRelationship(RelTypes.contributes_to, Direction.INCOMING) && !mRNA_node1.hasLabel(broken_protein_label) ){
                            p1 = (String)mRNA_node1.getProperty("protein");
                            mRNA_len1 = p1.length();
                            for (Relationship r2: current_gene_node.getRelationships(Direction.OUTGOING,RelTypes.codes_for)) {
                                mRNA_node2 = r2.getEndNode();
                                if ( mRNA_node2.hasRelationship(RelTypes.contributes_to, Direction.INCOMING) && !mRNA_node2.hasLabel(broken_protein_label)){ // some genes might code different types of RNAs like a mRNA and a lncRNA
                                    p2 = (String)mRNA_node2.getProperty("protein");
                                    mRNA_len2 = p2.length();
                                    if ( Math.abs(mRNA_len1 - mRNA_len2) <= Math.max(mRNA_len1, mRNA_len2)*LEN_FACTOR){
                                        are_connected = false;
                                        for (Relationship rel:gene_node.getRelationships(RelTypes.resembles))
                                            if(rel.getOtherNode(gene_node).equals(current_gene_node)){
                                                are_connected = true;
                                                r = rel;
                                                break;
                                            }
                                        if (!are_connected){
                                            similarity = pro_aligner.get_similarity(p1, p2);
                                            gene_node.createRelationshipTo(current_gene_node, RelTypes.resembles).setProperty("score", similarity);
                                        }
                                        else
                                            similarity = (double)r.getProperty("score");
                                        if ( similarity > THRESHOLD ) {
                                            gene_nodes.add(current_gene_node);
                                            found = true;
                                            if(current_gene_node.hasRelationship(RelTypes.contains, Direction.INCOMING)){
                                                gene_nodes.clear();
                                                return current_gene_node.getSingleRelationship(RelTypes.contains, Direction.INCOMING).getStartNode();
                                            }
                                            break;
                                        }
                                    }
                                }
                            } // for
                            if (found)
                                break;
                        } // if
                    } // for
                    gene_id = current_gene_id;
                }
            }// while
        }
        return null;
    }
    
    /**
     * 
     * @param gene_nodes
     * @param pq
     * @param gene_node
     * @param seq_aligner 
     */
    private Node get_gene_family(Queue<Node> gene_nodes, PriorityQueue<Long> pq, Node gene_node, SequenceAlignment seq_aligner){
        int RNA_len1,RNA_len2;
        double similarity;
        Node RNA_node1, RNA_node2, current_gene_node, node;
        Relationship r = null;
        String RNA_seq1, RNA_seq2, RNA_node1_type,RNA_node2_type, s1, s2;
        long gene_id, current_gene_id;
        boolean found, are_connected;
        pq.clear();
        for (Relationship r1: gene_node.getRelationships(Direction.OUTGOING,RelTypes.visits)) {
            node = r1.getEndNode();
            for (Relationship r2: node.getRelationships(Direction.INCOMING,RelTypes.visits)) {
                current_gene_node = r2.getStartNode();
            // avoid comparisons to the gene itself  
                if(!current_gene_node.equals(gene_node))  
                    pq.offer(current_gene_node.getId());
            }
        }
        if (!pq.isEmpty()) {
            gene_id = -1l;
        // for all the candidates with some shared node with the gene    
            while (!pq.isEmpty()) { 
                current_gene_id = pq.remove();
                if(gene_id != current_gene_id){
                    current_gene_node = graphDb.getNodeById(current_gene_id);
                    found = false;
                    for (Relationship r1: gene_node.getRelationships(Direction.OUTGOING,RelTypes.codes_for)) {
                        RNA_node1 = r1.getEndNode();
                        RNA_node1_type = (String)RNA_node1.getProperty("type");
                        for (Relationship r2: current_gene_node.getRelationships(Direction.OUTGOING,RelTypes.codes_for)) {
                            RNA_node2 = r2.getEndNode();
                            RNA_node2_type = (String)RNA_node2.getProperty("type");
                            if ( RNA_node1_type.equals(RNA_node2_type) && (RNA_node1_type.equals("tRNA") || RNA_node1_type.equals("rRNA"))){
                                RNA_seq1 = (String)RNA_node1.getProperty("sequence");
                                RNA_seq2 = (String)RNA_node2.getProperty("sequence");
                                RNA_len1 = RNA_seq1.length();
                                RNA_len2 = RNA_seq2.length();
                                if ( Math.abs(RNA_len1 - RNA_len2) <= Math.max(RNA_len1, RNA_len2)*LEN_FACTOR){
                                    are_connected = false;
                                    for (Relationship rel:gene_node.getRelationships(RelTypes.resembles))
                                        if(rel.getOtherNode(gene_node).equals(current_gene_node)){
                                            are_connected = true;
                                            r = rel;
                                            break;
                                        }
                                    if (!are_connected){
                                        similarity = seq_aligner.get_similarity(RNA_seq1,RNA_seq2);
                                        gene_node.createRelationshipTo(current_gene_node, RelTypes.resembles).setProperty("score", similarity);
                                    }
                                    else
                                        similarity = (double)r.getProperty("score");
                                    gene_node.createRelationshipTo(current_gene_node, RelTypes.resembles).setProperty("score", similarity);
                                    if ( similarity > THRESHOLD ) {
                                        gene_nodes.add(current_gene_node);
                                        found = true;
                                        if(current_gene_node.hasRelationship(RelTypes.contains, Direction.INCOMING)){
                                            gene_nodes.clear();
                                            return current_gene_node.getSingleRelationship(RelTypes.contains, Direction.INCOMING).getStartNode();
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                        if (found)
                            break;
                    }
                    gene_id = current_gene_id;
                }
            }// while
        }
        return null;
    }    

    /**
     * Remove the occurrence arrays of the edges.
     */    
    void drop_edges_of_type(RelationshipType type) {
        int i;
        ResourceIterator<Relationship> rels;
        Relationship r;
        try (Transaction tx = graphDb.beginTx()) {
            rels = graphDb.getAllRelationships().iterator();
            tx.success();
        }
        while (rels.hasNext()) {
            try (Transaction tx = graphDb.beginTx()) {
                for (i = 0; i < MAX_TRANSACTION_SIZE && rels.hasNext(); ++i) {
                    r = rels.next();
                    if (r.isType(type))
                        r.delete();
                }
                tx.success();
            }
        }
        rels.close();
    }    
    
    /**
     */    
    void finalize_groups() {
        int i, num_members, num_groups, num_super_groups = 0;
        int[] copy_number = new int[genomeDb.num_genomes+1];
        long group_id, current_group_id;
        double similarity;
        PriorityQueue<Long> pq = new PriorityQueue();
        ResourceIterator<Node> nodes;
        Node group_node, mate_group_node, gene_node, mate_gene_node, super_node;
        try (Transaction tx = graphDb.beginTx()) {
            nodes = graphDb.getAllNodes().iterator();
            tx.success();
        }
        while (nodes.hasNext()) {
            try (Transaction tx = graphDb.beginTx()) {
                for (i = 0; i < MAX_TRANSACTION_SIZE && nodes.hasNext(); ++i) {
                    group_node = nodes.next();
                    if (group_node.hasLabel(coding_group_lable) || group_node.hasLabel(noncoding_group_lable)){
                        num_members = 0;
                        for (Relationship rel: group_node.getRelationships(Direction.OUTGOING)){
                            ++num_members;
                            gene_node = rel.getEndNode();
                            ++copy_number[((int[])gene_node.getProperty("address"))[0]];
                            if (!group_node.hasRelationship(RelTypes.contains, Direction.INCOMING)) {
                                for (Relationship resemble: gene_node.getRelationships(RelTypes.resembles)){
                                    similarity = (double)resemble.getProperty("score");
                                    if (similarity > 1 - LEN_FACTOR){
                                        mate_gene_node = resemble.getOtherNode(gene_node);
                                        mate_group_node = mate_gene_node.getSingleRelationship(RelTypes.contains, Direction.INCOMING).getStartNode();
                                        if (!mate_group_node.equals(group_node) && !mate_group_node.hasRelationship(RelTypes.contains, Direction.INCOMING))
                                            pq.add(mate_group_node.getId());
                                    }
                                }
                            }
                        }
                        if (!pq.isEmpty()) {
                            ++num_super_groups;
                            super_node = graphDb.createNode(super_group_lable);
                            super_node.createRelationshipTo(group_node, RelTypes.contains);
                            group_id = -1l;
                            num_groups = 1;
                            while (!pq.isEmpty()) {
                                current_group_id = pq.remove();
                                if (group_id != current_group_id){
                                    super_node.createRelationshipTo(graphDb.getNodeById(current_group_id), RelTypes.contains);
                                    ++num_groups;
                                }
                                group_id = current_group_id;
                            }
                            super_node.setProperty("num_groups", num_groups);
                        }                        
                        group_node.setProperty("copy_number_variation", copy_number);
                        group_node.setProperty("num_members", num_members);
                        for (i=0; i<copy_number.length; ++i)
                            copy_number[i] = 0;
                    }
                }
                tx.success();
            }
        }
        nodes.close();
        System.out.println("Number of super groups:" + num_super_groups);
    }    
    
    /**
     * Shuts down the graph database if the program halts unexpectedly.
     * 
     * @param graphDb The graph database object 
     */
    private void registerShutdownHook(final GraphDatabaseService graphDb) {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                graphDb.shutdown();
            }
        });
    }
}
