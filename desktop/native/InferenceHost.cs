// Windows job owner: terminating Folio's host also terminates the inference tree.
// Build using the Windows .NET Framework compiler; no installer-time compilation.
using System;
using System.Runtime.InteropServices;
using System.Text;

class InferenceHost {
    [StructLayout(LayoutKind.Sequential)] struct IO_COUNTERS { public ulong ReadOperationCount, WriteOperationCount, OtherOperationCount, ReadTransferCount, WriteTransferCount, OtherTransferCount; }
    [StructLayout(LayoutKind.Sequential)] struct BASIC_LIMIT {
        public long PerProcessUserTimeLimit, PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize, MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass, SchedulingClass;
    }
    [StructLayout(LayoutKind.Sequential)] struct EXTENDED_LIMIT {
        public BASIC_LIMIT BasicLimitInformation; public IO_COUNTERS IoInfo;
        public UIntPtr ProcessMemoryLimit, JobMemoryLimit, PeakProcessMemoryUsed, PeakJobMemoryUsed;
    }
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct STARTUPINFO {
        public uint cb; public string lpReserved, lpDesktop, lpTitle;
        public uint dwX,dwY,dwXSize,dwYSize,dwXCountChars,dwYCountChars,dwFillAttribute,dwFlags;
        public ushort wShowWindow,cbReserved2; public IntPtr lpReserved2,hStdInput,hStdOutput,hStdError;
    }
    [StructLayout(LayoutKind.Sequential)] struct PROCESS_INFORMATION { public IntPtr hProcess,hThread; public uint dwProcessId,dwThreadId; }
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern IntPtr CreateJobObject(IntPtr attributes,string name);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job,int type,ref EXTENDED_LIMIT info,uint length);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool CreateProcess(string application,StringBuilder command,IntPtr processAttributes,IntPtr threadAttributes,bool inherit,uint flags,IntPtr environment,string directory,ref STARTUPINFO startup,out PROCESS_INFORMATION process);
    [DllImport("kernel32.dll")] static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll")] static extern uint WaitForSingleObject(IntPtr handle,uint milliseconds);
    [DllImport("kernel32.dll")] static extern uint WaitForMultipleObjects(uint count,IntPtr[] handles,bool all,uint milliseconds);
    [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr OpenProcess(uint access,bool inherit,uint pid);
    [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr process,out uint code);
    [DllImport("kernel32.dll")] static extern bool TerminateProcess(IntPtr process,uint code);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    // Standard Windows argv quoting; never invoke cmd.exe or a shell.
    static string Quote(string arg) {
        var value=new StringBuilder("\""); int slashes=0;
        foreach(char c in arg) {
            if(c=='\\') { slashes++; continue; }
            if(c=='\"') { value.Append('\\',slashes*2+1); value.Append(c); }
            else { value.Append('\\',slashes); value.Append(c); }
            slashes=0;
        }
        value.Append('\\',slashes*2); return value.Append('"').ToString();
    }
    static int Main(string[] args) {
        if(args.Length<1 || !System.IO.Path.IsPathRooted(args[0]) || !System.IO.File.Exists(args[0])) return 2;
        uint ownerPid;
        if(!uint.TryParse(Environment.GetEnvironmentVariable("FOLIO_INFERENCE_PARENT_PID"),out ownerPid)) return 9;
        IntPtr owner=OpenProcess(0x00100000,false,ownerPid); // SYNCHRONIZE, held handle avoids PID reuse.
        if(owner==IntPtr.Zero) return 10;
        IntPtr job=CreateJobObject(IntPtr.Zero,null);
        if(job==IntPtr.Zero) { CloseHandle(owner); return 3; }
        var process=new PROCESS_INFORMATION();
        try {
            var limit=new EXTENDED_LIMIT(); limit.BasicLimitInformation.LimitFlags=0x2000; // KILL_ON_JOB_CLOSE
            if(!SetInformationJobObject(job,9,ref limit,(uint)Marshal.SizeOf(typeof(EXTENDED_LIMIT)))) return 4;
            var startup=new STARTUPINFO(); startup.cb=(uint)Marshal.SizeOf(typeof(STARTUPINFO));
            var command=new StringBuilder(); foreach(string arg in args) { if(command.Length>0)command.Append(' '); command.Append(Quote(arg)); }
            // Start suspended: it cannot escape before assignment to the job.
            if(!CreateProcess(args[0],command,IntPtr.Zero,IntPtr.Zero,false,0x08000004,IntPtr.Zero,System.IO.Path.GetDirectoryName(args[0]),ref startup,out process)) return 5;
            if(!AssignProcessToJobObject(job,process.hProcess)) { TerminateProcess(process.hProcess,6); return 6; }
            if(ResumeThread(process.hThread)==0xffffffff) return 7;
            uint ended=WaitForMultipleObjects(2,new IntPtr[]{process.hProcess,owner},false,0xffffffff);
            if(ended==1) return 0; // Parent crashed: finally closes the job and kills the inference tree.
            if(ended!=0) return 11;
            uint code; return GetExitCodeProcess(process.hProcess,out code)?unchecked((int)code):8;
        } finally {
            CloseHandle(job);
            CloseHandle(owner);
            if(process.hThread!=IntPtr.Zero)CloseHandle(process.hThread);
            if(process.hProcess!=IntPtr.Zero)CloseHandle(process.hProcess);
        }
    }
}
