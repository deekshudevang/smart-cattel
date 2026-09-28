import os
import sqlite3
import pandas as pd
import numpy as np
import json
import datetime
import joblib
from sklearn.model_selection import train_test_split, GroupShuffleSplit
from sklearn.ensemble import RandomForestClassifier
from sklearn.multioutput import MultiOutputClassifier
from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score, roc_auc_score, confusion_matrix

class CattleMLPipeline:
    def __init__(self, db_path=None, model_dir=None):
        self.db_path = db_path or os.path.join(os.path.dirname(__file__), "..", "backend", "smart_cattle.db")
        self.model_dir = model_dir or os.path.join(os.path.dirname(__file__), "models")
        os.makedirs(self.model_dir, exist_ok=True)
        
        self.features = [
            "spo2", "heart_rate", "temperature", "humidity",
            "mems_x", "mems_y", "mems_z", "acceleration_magnitude",
            "ph", "ldr", "activity", "temp_baseline_dev",
            "hr_baseline_dev", "spo2_baseline_dev", "activity_baseline_dev"
        ]
        self.labels = ["overall_status"]
        self.version = datetime.datetime.now().strftime("%Y%m%d%H%M%S")

    def dataset_ingestion(self):
        """1. Dataset Ingestion"""
        if os.path.exists(self.db_path):
            try:
                conn = sqlite3.connect(self.db_path)
                query = """
                    SELECT r.*, p.spo2_status, p.heart_rate_status, p.temperature_status, 
                           p.mems_status, p.ph_status, p.ldr_status, p.overall_status,
                           d.cattle_id
                    FROM sensor_readings r
                    JOIN predictions p ON r.id = p.reading_id
                    LEFT JOIN devices d ON r.device_id = d.device_id
                """
                df = pd.read_sql_query(query, conn)
                df = df.loc[:,~df.columns.duplicated()]
                conn.close()
                return df
            except Exception as e:
                print(f"Failed to load from DB: {e}")
        
        raise FileNotFoundError("No valid DB found. Synthetic dataset generation is disabled.")

    def data_cleaning(self, df):
        """2. Cleaning"""
        # Drop rows with too many missing values instead of 0-filling
        df = df.dropna(thresh=len(self.features) - 3) # keep rows with at least some features
        # For remaining missing values, we can impute with median for numeric columns
        for col in ["spo2", "heart_rate", "temperature", "humidity", "mems_x", "mems_y", "mems_z", "ph", "ldr"]:
            if col in df.columns:
                df[col] = df[col].fillna(df[col].median())
        return df

    def data_validation(self, df):
        """3. Validation"""
        required_cols = ["spo2", "heart_rate", "temperature", "mems_x", "mems_y", "mems_z", "ph", "ldr"] + self.labels
        for col in required_cols:
            if col not in df.columns:
                raise ValueError(f"Missing required column: {col}")
        return df

    def feature_engineering(self, df):
        """4. Feature Engineering"""
        # Calculate acceleration magnitude if not present
        if "acceleration_magnitude" not in df.columns:
            df["acceleration_magnitude"] = np.sqrt(df["mems_x"]**2 + df["mems_y"]**2 + df["mems_z"]**2)
        
        # Calculate activity score based on magnitude
        if "activity" not in df.columns:
            df["activity"] = (df["acceleration_magnitude"] - 9.81).abs()
        
        # Baseline deviations (placeholder logic - in real world would use baseline engine output)
        if "temp_baseline_dev" not in df.columns:
            df["temp_baseline_dev"] = 0.0
        if "hr_baseline_dev" not in df.columns:
            df["hr_baseline_dev"] = 0.0
        if "spo2_baseline_dev" not in df.columns:
            df["spo2_baseline_dev"] = 0.0
        if "activity_baseline_dev" not in df.columns:
            df["activity_baseline_dev"] = 0.0

        # Ensure all expected features exist
        for f in self.features:
            if f not in df.columns:
                df[f] = 0.0
                
        # Encode labels to binary (0: normal, 1: abnormal)
        for label in self.labels:
            df[label] = (df[label] == "abnormal").astype(int)
            
        return df

    def split_data(self, df):
        """5. Train/Validation/Test Split with Grouping"""
        if "cattle_id" not in df.columns or df["cattle_id"].isnull().all():
            df["cattle_id"] = np.random.randint(0, 10, size=len(df))
        df["cattle_id"] = df["cattle_id"].fillna("unknown_cattle")
            
        X = df[self.features]
        Y = df[self.labels]
        groups = df["cattle_id"]
        
        # Fallback if we don't have enough groups
        if len(groups.unique()) < 3:
            X_temp, X_test, Y_temp, Y_test = train_test_split(X, Y, test_size=0.15, random_state=42)
            X_train, X_val, Y_train, Y_val = train_test_split(X_temp, Y_temp, test_size=0.1765, random_state=42)
            return X_train, X_val, X_test, Y_train, Y_val, Y_test
            
        gss = GroupShuffleSplit(n_splits=1, test_size=0.3, random_state=42)
        train_idx, temp_idx = next(gss.split(X, Y, groups))
        
        X_train, Y_train = X.iloc[train_idx], Y.iloc[train_idx]
        X_temp, Y_temp, groups_temp = X.iloc[temp_idx], Y.iloc[temp_idx], groups.iloc[temp_idx]
        
        gss_val = GroupShuffleSplit(n_splits=1, test_size=0.5, random_state=42)
        val_idx, test_idx = next(gss_val.split(X_temp, Y_temp, groups_temp))
        
        X_val, Y_val = X_temp.iloc[val_idx], Y_temp.iloc[val_idx]
        X_test, Y_test = X_temp.iloc[test_idx], Y_temp.iloc[test_idx]
        
        return X_train, X_val, X_test, Y_train, Y_val, Y_test

    def train_model(self, X_train, Y_train):
        """6. Training"""
        model = RandomForestClassifier(n_estimators=100, random_state=42, max_depth=10)
        model.fit(X_train, Y_train.values.ravel())
        return model

    def evaluate_model(self, model, X_test, Y_test):
        """7. Evaluation"""
        Y_pred = model.predict(X_test)
        
        metrics = {}
        for i, label in enumerate(self.labels):
            y_true_col = Y_test.iloc[:, i]
            y_pred_col = Y_pred if len(self.labels) == 1 else Y_pred[:, i]
            
            acc = accuracy_score(y_true_col, y_pred_col)
            prec = precision_score(y_true_col, y_pred_col, zero_division=0)
            rec = recall_score(y_true_col, y_pred_col, zero_division=0)
            f1 = f1_score(y_true_col, y_pred_col, zero_division=0)
            cm = confusion_matrix(y_true_col, y_pred_col).tolist()
            
            try:
                if len(np.unique(y_true_col)) > 1:
                    roc_auc = roc_auc_score(y_true_col, y_pred_col)
                else:
                    roc_auc = None
            except Exception:
                roc_auc = None
                
            metrics[label] = {
                "accuracy": acc,
                "precision": prec,
                "recall": rec,
                "f1_score": f1,
                "roc_auc": roc_auc,
                "confusion_matrix": cm
            }
        return metrics

    def model_versioning(self, model, metrics):
        """8. Model Versioning"""
        model_path = os.path.join(self.model_dir, "multivariate_model.pkl")
        joblib.dump(model, model_path)
        
        metadata = {
            "version": self.version,
            "timestamp": datetime.datetime.now().isoformat(),
            "model_type": "RandomForestClassifier",
            "labels": self.labels
        }
        meta_path = os.path.join(self.model_dir, "metadata.json")
        with open(meta_path, "w") as f:
            json.dump(metadata, f, indent=4)
            
        feature_schema = {
            "features": self.features,
            "version": self.version
        }
        schema_path = os.path.join(self.model_dir, "feature_schema.json")
        with open(schema_path, "w") as f:
            json.dump(feature_schema, f, indent=4)
            
        metrics_path = os.path.join(self.model_dir, "metrics.json")
        with open(metrics_path, "w") as f:
            json.dump({"version": self.version, "metrics": metrics}, f, indent=4)
        
        print(f"Model saved to {model_path}")
        print(f"Metadata saved to {meta_path}")

    def run(self):
        print("Starting ML Pipeline...")
        df = self.dataset_ingestion()
        print(f"Ingested {len(df)} rows.")
        df = self.data_cleaning(df)
        df = self.data_validation(df)
        df = self.feature_engineering(df)
        print("Feature engineering complete.")
        
        X_train, X_val, X_test, Y_train, Y_val, Y_test = self.split_data(df)
        print(f"Training on {len(X_train)} samples, validating on {len(X_val)}, testing on {len(X_test)}.")
        
        model = self.train_model(X_train, Y_train)
        print("Training complete.")
        
        # Evaluate on validation or test set
        metrics = self.evaluate_model(model, X_test, Y_test)
        print("Evaluation complete.")
        
        self.model_versioning(model, metrics)
        print("Pipeline finished successfully.")

if __name__ == "__main__":
    pipeline = CattleMLPipeline()
    pipeline.run()
