package com.sofia.codeexplorer.model;

public class ClassNode {

    private final String qualifiedName;
    private final String simpleName;
    private final String packageName;
    private final NodeType type;
    private final String filePath;

    private int    fanIn               = 0;
    private int    fanOut              = 0;
    private double instability         = 0.0;
    private int    noc                 = 0;
    private int    dit                 = 0;
    private int    implementationsCount = 0;

    public ClassNode(String qualifiedName, String simpleName, String packageName,
                     NodeType type, String filePath) {
        this.qualifiedName = qualifiedName;
        this.simpleName    = simpleName;
        this.packageName   = packageName;
        this.type          = type;
        this.filePath      = filePath;
    }

    public String  getQualifiedName()        { return qualifiedName; }
    public String  getSimpleName()           { return simpleName; }
    public String  getPackageName()          { return packageName; }
    public NodeType getType()                { return type; }
    public String  getFilePath()             { return filePath; }
    public int     getFanIn()                { return fanIn; }
    public int     getFanOut()               { return fanOut; }
    public double  getInstability()          { return instability; }
    public int     getNoc()                  { return noc; }
    public int     getDit()                  { return dit; }
    public int     getImplementationsCount() { return implementationsCount; }

    public void setFanIn(int v)               { this.fanIn               = v; }
    public void setFanOut(int v)              { this.fanOut              = v; }
    public void setInstability(double v)      { this.instability         = v; }
    public void setNoc(int v)                 { this.noc                 = v; }
    public void setDit(int v)                 { this.dit                 = v; }
    public void setImplementationsCount(int v){ this.implementationsCount = v; }
}
