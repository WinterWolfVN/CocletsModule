package android.os;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public final class SharedMemory implements Parcelable, Closeable {

    private final FileDescriptor mFileDescriptor;
    private final int mSize;
    private final MemoryFile mMemoryFile;

    private SharedMemory(FileDescriptor fd, int size, MemoryFile memoryFile) {
        this.mFileDescriptor = fd;
        this.mSize = size;
        this.mMemoryFile = memoryFile;
    }

    public static SharedMemory create(String name, int size) throws Exception {
        if (size <= 0) throw new IllegalArgumentException();
        MemoryFile memoryFile = new MemoryFile(name, size);
        Method getFdMethod = MemoryFile.class.getDeclaredMethod("getFileDescriptor");
        getFdMethod.setAccessible(true);
        FileDescriptor fd = (FileDescriptor) getFdMethod.invoke(memoryFile);
        return new SharedMemory(fd, size, memoryFile);
    }

    public static ClassLoader InMemoryDexClassLoader(ByteBuffer[] buffers, ClassLoader parent) {
        try {
            int totalSize = 0;
            for (ByteBuffer buf : buffers) totalSize += buf.remaining();
            byte[] dexBytes = new byte[totalSize];
            int offset = 0;
            for (ByteBuffer buf : buffers) {
                int len = buf.remaining();
                buf.get(dexBytes, offset, len);
                offset += len;
            }

            Class<?> dexFileClass = Class.forName("dalvik.system.DexFile");
            Method openDexFileMethod = dexFileClass.getDeclaredMethod("openDexFile", byte[].class);
            openDexFileMethod.setAccessible(true);
            Object cookie = openDexFileMethod.invoke(null, dexBytes);

            dalvik.system.DexClassLoader dummyLoader = new dalvik.system.DexClassLoader("", null, null, parent);
            Field pathListField = Class.forName("dalvik.system.BaseDexClassLoader").getDeclaredField("pathList");
            pathListField.setAccessible(true);
            Object pathList = pathListField.get(dummyLoader);

            Field dexElementsField = pathList.getClass().getDeclaredField("dexElements");
            dexElementsField.setAccessible(true);
            Object[] dexElements = (Object[]) dexElementsField.get(pathList);

            Field dexFileField = dexElements[0].getClass().getDeclaredField("dexFile");
            dexFileField.setAccessible(true);
            Object dexFileObj = dexFileField.get(dexElements[0]);

            Field mCookieField = dexFileClass.getDeclaredField("mCookie");
            mCookieField.setAccessible(true);
            mCookieField.set(dexFileObj, cookie);

            return dummyLoader;
        } catch (Throwable e) {
            return parent;
        }
    }

    public ByteBuffer mapReadOnly() throws Exception {
        if (mFileDescriptor == null || !mFileDescriptor.valid()) throw new IllegalStateException();
        return new FileInputStream(mFileDescriptor).getChannel().map(FileChannel.MapMode.READ_ONLY, 0, mSize);
    }

    public void setProtect(int prot) {
        try {
            Class<?> libcore = Class.forName("libcore.io.Libcore");
            Field osField = libcore.getField("os");
            Object os = osField.get(null);
            Method mprotect = os.getClass().getMethod("mprotect", long.class, long.class, int.class);
            
            FileInputStream fis = new FileInputStream(mFileDescriptor);
            ByteBuffer buffer = fis.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, mSize);
            
            Field addressField = java.nio.Buffer.class.getDeclaredField("address");
            addressField.setAccessible(true);
            long address = addressField.getLong(buffer);
            mprotect.invoke(os, address, (long) mSize, prot);
        } catch (Throwable ignored) {}
    }

    @Override public void close() { if (mMemoryFile != null) mMemoryFile.close(); }
    public int getSize() { return mSize; }
    public FileDescriptor getFileDescriptor() { return mFileDescriptor; }
    @Override public int describeContents() { return 1; }
    @Override public void writeToParcel(Parcel dest, int flags) {}
    public static final Parcelable.Creator<SharedMemory> CREATOR = new Parcelable.Creator<SharedMemory>() {
        @Override public SharedMemory createFromParcel(Parcel source) { return null; }
        @Override public SharedMemory[] newArray(int size) { return new SharedMemory[size]; }
    };
 }
