# Smart Cattle Machine Learning Pipeline

## Overview
The Smart Cattle ML Pipeline has been re-architected to use a unified multivariate approach, preventing the issue of evaluating features in isolation when multiple sensor inputs provide better context.

## Pipeline Architecture
The pipeline is structured into reproducible stages:

1. **Dataset**: Ingestion of raw sensor data from the database or external dataset files.
2. **Cleaning**: Handling of null values, missing data, and invalid sensor readings. Missing values are imputed or rows dropped as configured, avoiding 0-filling where 0 is semantically incorrect.
3. **Validation**: Data schema checks to ensure required columns are present and data types are valid.
4. **Feature Engineering**: Calculation of new features such as `acceleration_magnitude` (from MEMS x, y, z), `activity` states, and historical deviations where temporal data is available.
5. **Train/Validation/Test Split**: 70/15/15 splitting maintaining temporal consistency if applicable.
6. **Training**: Using multivariate data (SpO2, BPM, Temperature, Humidity, MEMS, pH, LDR, Activity) to train models, as opposed to isolated single-feature predictors.
7. **Evaluation**: Computing standard classification metrics: Accuracy, Precision, Recall, F1-Score, ROC-AUC, and Confusion Matrix.
8. **Model Versioning**: Serializing the trained model alongside its metrics, feature schema, version, and training timestamp to `models/metadata.json` and `models/multivariate_model.pkl`.
9. **Inference**: Using the versioned model in production to predict cattle health status based on the unified feature schema.

## Features
- `spo2`
- `bpm` (Heart Rate)
- `temperature`
- `humidity`
- `mems_x`, `mems_y`, `mems_z`
- `acceleration_magnitude`
- `ph`
- `ldr`
- `activity`
- `historical_deviations` (where available)

## Metrics Tracked
- **Accuracy**
- **Precision**
- **Recall**
- **F1 Score**
- **ROC-AUC**
- **Confusion Matrix**

## Artifacts Produced
- `multivariate_model.pkl`: The trained predictive model (using Scikit-Learn).
- `metadata.json`: Contains the model version, training timestamp, feature schema, and evaluation metrics.
